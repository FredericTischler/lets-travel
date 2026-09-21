package com.travelplan.identity;

import com.travelplan.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the report (signalement) resource —
 * docs/lets-travel-architecture-decisions.md §5:
 *
 * <ul>
 *   <li>{@code POST /reports} — any authenticated role may file a report;
 *       {@code reporterId} is always the caller's own id (resolved from the
 *       token, never the request body); self-reporting is rejected (400);
 *       reporting a non-existent/soft-deleted user is rejected (404).</li>
 *   <li>{@code GET /reports} — ADMIN-only.</li>
 *   <li>{@code PATCH /reports/{id}/status} — ADMIN-only, OPEN -&gt;
 *       REVIEWED/DISMISSED/ACTIONED, terminal once resolved.</li>
 *   <li>{@code GET /reports/count/{userId}} — any authenticated role.</li>
 * </ul>
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReportsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.5-bookworm")
                    .withDatabaseName("identity_db")
                    .withUsername("identity_user")
                    .withPassword("test_password_only");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", postgres::getHost);
        registry.add("DB_PORT", () -> String.valueOf(postgres.getMappedPort(5432)));
        registry.add("DB_NAME", postgres::getDatabaseName);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("JWT_SIGNING_KEY", () -> "test-only-signing-key-must-be-at-least-32-bytes-long");
        // Required since application.yml stopped hiding a missing PAYMENT_SERVICE_URL behind a
        // literal default; nothing listens here (only the cascade-delete tests care).
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:1");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private TestAccounts accounts;

    @BeforeEach
    void setUpAccounts() {
        accounts = new TestAccounts(restTemplate, userRepository, passwordEncoder);
    }

    @Test
    void postReportsWithoutAuthorizationHeaderReturns401() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/reports", Map.of("reportedUserId", UUID.randomUUID().toString(), "reason", "spam"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "Invalid or missing token");
    }

    @Test
    void aTravelerCanFileAReportAgainstAnotherUserAndReporterIdComesFromTheToken() {
        String reporterToken = createUserAndLogin("reporter-1@example.com", "TRAVELER");
        String reportedId = createUser("reported-1@example.com", "TRAVEL_MANAGER");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + reporterToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/reports", HttpMethod.POST,
                new HttpEntity<>(Map.of("reportedUserId", reportedId, "reason", "inappropriate behaviour"), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("reportedUserId", reportedId);
        assertThat(response.getBody()).containsEntry("status", "OPEN");
        assertThat(response.getBody().get("reporterId")).isNotEqualTo(reportedId);
    }

    @Test
    void reportingYourselfReturns400() {
        Map<String, Object> created = createUserRaw("self-reporter@example.com", "TRAVELER");
        String token = login("self-reporter@example.com", "secret123");
        String selfId = (String) created.get("id");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/reports", HttpMethod.POST,
                new HttpEntity<>(Map.of("reportedUserId", selfId, "reason", "n/a"), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "A user cannot report themselves");
    }

    @Test
    void reportingANonExistentUserReturns404() {
        String reporterToken = createUserAndLogin("reporter-2@example.com", "TRAVELER");
        String unknownId = UUID.randomUUID().toString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + reporterToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/reports", HttpMethod.POST,
                new HttpEntity<>(Map.of("reportedUserId", unknownId, "reason", "n/a"), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReportsWithoutAdminRoleReturns403() {
        String travelerToken = createUserAndLogin("non-admin@example.com", "TRAVELER");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + travelerToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/reports", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanListAllReports() {
        String reporterToken = createUserAndLogin("reporter-3@example.com", "TRAVELER");
        String reportedId = createUser("reported-3@example.com", "TRAVELER");
        fileReport(reporterToken, reportedId, "spam");

        String adminToken = createUserAndLogin("admin-list@example.com", "ADMIN");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + adminToken);
        ResponseEntity<List> response = restTemplate.exchange(
                "/reports", HttpMethod.GET, new HttpEntity<>(headers), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().stream()
                .anyMatch(r -> reportedId.equals(((Map<?, ?>) r).get("reportedUserId")))).isTrue();
    }

    @Test
    void adminCanTransitionAnOpenReportToDismissedAndItBecomesImmutable() {
        String reporterToken = createUserAndLogin("reporter-4@example.com", "TRAVELER");
        String reportedId = createUser("reported-4@example.com", "TRAVELER");
        String reportId = fileReport(reporterToken, reportedId, "spam");

        String adminToken = createUserAndLogin("admin-transition@example.com", "ADMIN");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + adminToken);

        ResponseEntity<Map> firstTransition = restTemplate.exchange(
                "/reports/" + reportId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "DISMISSED"), headers), Map.class);
        assertThat(firstTransition.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstTransition.getBody()).containsEntry("status", "DISMISSED");

        ResponseEntity<Map> secondTransition = restTemplate.exchange(
                "/reports/" + reportId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "ACTIONED"), headers), Map.class);
        assertThat(secondTransition.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateStatusWithAnInvalidTargetValueReturns400() {
        String reporterToken = createUserAndLogin("reporter-5@example.com", "TRAVELER");
        String reportedId = createUser("reported-5@example.com", "TRAVELER");
        String reportId = fileReport(reporterToken, reportedId, "spam");

        String adminToken = createUserAndLogin("admin-invalid@example.com", "ADMIN");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + adminToken);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/reports/" + reportId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "OPEN"), headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateStatusWithoutAdminRoleReturns403() {
        String reporterToken = createUserAndLogin("reporter-6@example.com", "TRAVELER");
        String reportedId = createUser("reported-6@example.com", "TRAVELER");
        String reportId = fileReport(reporterToken, reportedId, "spam");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + reporterToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/reports/" + reportId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "DISMISSED"), headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void countByReportedUserIsAccessibleToAnyAuthenticatedRoleAndReflectsFiledReports() {
        String reporterToken = createUserAndLogin("reporter-7@example.com", "TRAVELER");
        String reportedId = createUser("reported-7@example.com", "TRAVEL_MANAGER");
        fileReport(reporterToken, reportedId, "spam");

        String someOtherTravelerToken = createUserAndLogin("bystander@example.com", "TRAVELER");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + someOtherTravelerToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/reports/count/" + reportedId, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("count")).longValue()).isEqualTo(1L);
    }

    @Test
    void countByReportedUserForAnUnknownUserIdReturnsZeroNotAnError() {
        String token = createUserAndLogin("count-unknown@example.com", "TRAVELER");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/reports/count/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("count")).longValue()).isEqualTo(0L);
    }

    // --- helpers ---

    private Map<String, Object> createUserRaw(String email, String role) {
        if ("ADMIN".equals(role)) {
            // An ADMIN cannot be created through the public POST /users any more.
            UUID id = accounts.createAdmin(email);
            return Map.of("id", id.toString(), "email", email, "role", "ADMIN");
        }
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/users", Map.of("email", email, "password", "secret123", "role", role), Map.class);
        return response.getBody();
    }

    private String createUser(String email, String role) {
        return (String) createUserRaw(email, role).get("id");
    }

    private String login(String email, String password) {
        ResponseEntity<Map> loginResponse = restTemplate.postForEntity(
                "/login", Map.of("email", email, "password", password), Map.class);
        return (String) loginResponse.getBody().get("token");
    }

    private String createUserAndLogin(String email, String role) {
        createUserRaw(email, role);
        return login(email, "secret123");
    }

    private String fileReport(String reporterToken, String reportedUserId, String reason) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + reporterToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/reports", HttpMethod.POST,
                new HttpEntity<>(Map.of("reportedUserId", reportedUserId, "reason", reason), headers),
                Map.class);
        return (String) response.getBody().get("id");
    }
}
