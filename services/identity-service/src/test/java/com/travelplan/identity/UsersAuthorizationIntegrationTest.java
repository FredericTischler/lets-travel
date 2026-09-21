package com.travelplan.identity;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the fixes applied to the user resource:
 *
 * <ul>
 *   <li>{@code GET /users} (and the other previously-open routes) now require
 *       the caller to be an administrator (see {@code AuthService#requireAdmin}):
 *       a valid token whose subject maps to an active user AND whose
 *       {@code role} claim is {@code ADMIN} (docs/sujet.md §4, least
 *       privilege — enforcement, not just authentication).</li>
 *   <li>An admin account cannot come from the public {@code POST /users}
 *       any more: these tests get theirs from {@link TestAccounts}.</li>
 *   <li>CORS is wired for the admin dashboard's origins (see CorsConfig),
 *       verified here via a preflight (OPTIONS) request.</li>
 * </ul>
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class UsersAuthorizationIntegrationTest {

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
    void getUsersWithoutAuthorizationHeaderReturns401() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/users", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "Invalid or missing token");
    }

    @Test
    void getUsersWithAValidTokenReturns200AndPreservesTheExistingBehaviour() {
        String email = "list-ok@example.com";
        accounts.createAdmin(email);
        String token = accounts.login(email, TestAccounts.DEFAULT_PASSWORD);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        ResponseEntity<List> response = restTemplate.exchange(
                "/users", HttpMethod.GET, new HttpEntity<>(headers), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().stream().anyMatch(u -> ((Map<?, ?>) u).get("email").equals(email))).isTrue();
    }

    @Test
    void getUserByIdAndDeleteUserBothRequireAValidToken() {
        String email = "detail-protected@example.com";
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                "/users", Map.of("email", email, "password", "secret123"), Map.class);
        String id = (String) createResponse.getBody().get("id");

        ResponseEntity<Map> getWithoutToken = restTemplate.getForEntity("/users/" + id, Map.class);
        assertThat(getWithoutToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Void> deleteWithoutToken =
                restTemplate.exchange("/users/" + id, HttpMethod.DELETE, null, Void.class);
        assertThat(deleteWithoutToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void postUsersAndLoginRemainPublic() {
        // POST /users with no Authorization header must still succeed (201) —
        // it is the only way to create the very first account.
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                "/users", Map.of("email", "still-public@example.com", "password", "secret123"), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // /login with no Authorization header must still be reachable (its
        // failure mode is 401 for bad credentials, not "no route"/403).
        ResponseEntity<Map> loginResponse = restTemplate.postForEntity(
                "/login", Map.of("email", "still-public@example.com", "password", "secret123"), Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void preflightFromAnAllowedDashboardOriginSucceeds() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:4200");
        headers.set("Access-Control-Request-Method", "GET");
        headers.set("Access-Control-Request-Headers", "Authorization");

        ResponseEntity<Void> response = restTemplate.exchange(
                "/users", HttpMethod.OPTIONS, new HttpEntity<>(headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:4200");
        assertThat(response.getHeaders().getAccessControlAllowMethods())
                .contains(HttpMethod.GET, HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE);
    }

    @Test
    void preflightFromADisallowedOriginCarriesNoAllowOriginHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://evil.example.com");
        headers.set("Access-Control-Request-Method", "GET");

        ResponseEntity<Void> response = restTemplate.exchange(
                "/users", HttpMethod.OPTIONS, new HttpEntity<>(headers), Void.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void getUsersWithATokenCarryingNoRoleClaimReturns403() {
        String email = "no-role-claim@example.com";
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                "/users", Map.of("email", email, "password", "secret123"), Map.class);
        String userId = (String) createResponse.getBody().get("id");

        // Same subject as a real active user, same signing key, valid
        // signature and expiration — but no "role" claim at all. Simulates a
        // pre-least-privilege token or a forged one missing the claim.
        String tokenWithoutRole = signToken(userId, null);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + tokenWithoutRole);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/users", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Administrator role required");
    }

    @Test
    void getUsersWithATokenCarryingTheAdminRoleClaimReturns200() {
        String email = "role-admin-claim@example.com";
        accounts.createAdmin(email);
        String token = accounts.login(email, TestAccounts.DEFAULT_PASSWORD);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        ResponseEntity<List> response = restTemplate.exchange(
                "/users", HttpMethod.GET, new HttpEntity<>(headers), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void everyAdminOnlyRouteRefusesATravelerAndATravelManagerWith403() {
        for (String role : List.of("TRAVELER", "TRAVEL_MANAGER")) {
            String email = "not-admin-" + role.toLowerCase() + "@example.com";
            ResponseEntity<Map> created = restTemplate.postForEntity(
                    "/users", Map.of("email", email, "password", "secret123", "role", role), Map.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String id = (String) created.getBody().get("id");
            HttpEntity<Void> auth = new HttpEntity<>(TestAccounts.bearer(accounts.login(email, "secret123")));

            assertThat(restTemplate.exchange("/users", HttpMethod.GET, auth, Map.class).getStatusCode())
                    .as("GET /users as %s", role).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(restTemplate.exchange("/users/" + id, HttpMethod.GET, auth, Map.class).getStatusCode())
                    .as("GET /users/{id} as %s", role).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(restTemplate.exchange("/users/" + id, HttpMethod.DELETE, auth, Map.class).getStatusCode())
                    .as("DELETE /users/{id} as %s", role).isEqualTo(HttpStatus.FORBIDDEN);
            HttpEntity<Map<String, String>> patch = new HttpEntity<>(
                    Map.of("email", "changed-" + email), TestAccounts.bearer(accounts.login(email, "secret123")));
            assertThat(restTemplate.exchange("/users/" + id, HttpMethod.PATCH, patch, Map.class).getStatusCode())
                    .as("PATCH /users/{id} as %s", role).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Signs a token with the exact same key/algorithm as the application
     * under test (see {@code JWT_SIGNING_KEY} above), with full control over
     * the {@code role} claim — used to prove the role check is enforced
     * independently from mere signature/subject validity.
     */
    private static String signToken(String subject, String role) {
        SecretKey key = Keys.hmacShaKeyFor(
                "test-only-signing-key-must-be-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))));
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }
}