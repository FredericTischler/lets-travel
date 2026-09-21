package com.travelplan.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the 3-role support introduced in
 * docs/lets-travel-architecture-decisions.md §1: {@code POST /users} now
 * accepts an explicit {@code role} (one of ADMIN/TRAVEL_MANAGER/TRAVELER),
 * defaults to TRAVELER when omitted (least privilege), and rejects any other
 * value with a 400. Who may ask for ADMIN is covered by
 * {@link AdminCreationPrivilegeIntegrationTest}. The
 * chosen role must show up in the JWT issued at login.
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class RoleAssignmentIntegrationTest {

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

    @Test
    void creatingAUserWithoutARoleDefaultsToTravelerNotAdmin() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/users", Map.of("email", "no-role@example.com", "password", "secret123"), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).containsEntry("role", "TRAVELER");
    }

    @Test
    void creatingATravelManagerAndATravelerSucceedsAndPersistsTheRole() {
        ResponseEntity<Map> managerResponse = restTemplate.postForEntity(
                "/users", Map.of("email", "manager@example.com", "password", "secret123", "role", "TRAVEL_MANAGER"),
                Map.class);
        assertThat(managerResponse.getBody()).containsEntry("role", "TRAVEL_MANAGER");

        ResponseEntity<Map> travelerResponse = restTemplate.postForEntity(
                "/users", Map.of("email", "traveler@example.com", "password", "secret123", "role", "TRAVELER"),
                Map.class);
        assertThat(travelerResponse.getBody()).containsEntry("role", "TRAVELER");
    }

    @Test
    void anInvalidRoleIsRejectedWith400() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/users", Map.of("email", "bad-role@example.com", "password", "secret123", "role", "SUPERUSER"),
                Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void theChosenRoleIsCarriedByTheLoginJwt() {
        restTemplate.postForEntity(
                "/users", Map.of("email", "role-in-jwt@example.com", "password", "secret123", "role", "TRAVELER"),
                Map.class);

        ResponseEntity<Map> loginResponse = restTemplate.postForEntity(
                "/login", Map.of("email", "role-in-jwt@example.com", "password", "secret123"), Map.class);
        String token = (String) loginResponse.getBody().get("token");

        assertThat(decodeRoleClaim(token)).isEqualTo("TRAVELER");
    }

    /** Decodes the unsigned JWT payload just enough to read the {@code role} claim — test-only, no signature check. */
    private static String decodeRoleClaim(String jwt) {
        String payloadSegment = jwt.split("\\.")[1];
        String json = new String(Base64.getUrlDecoder().decode(payloadSegment));
        return json.replaceAll(".*\"role\":\"([^\"]+)\".*", "$1");
    }
}
