package com.travelplan.identity;

import com.travelplan.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the full user lifecycle:
 *   create → delete → verify deletion → re-create (same email) → conflict
 *
 * Exercises the soft-delete contract end-to-end:
 * - A soft-deleted user does NOT block re-registration (partial unique index).
 * - An ACTIVE user DOES block re-registration (HTTP 409).
 *
 * GET /users/{id} and DELETE /users/{id} require an ADMIN token (see
 * UserController) — this test obtains a throwaway admin from
 * {@link TestAccounts} (an admin can no longer be created through the public
 * POST /users) and reuses its token for every authenticated call.
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 * DynamicPropertySource satisfies the :? fail-fast guards in application.yml.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class UserLifecycleIntegrationTest {

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
        // Not exercised by this test's assertions (only that DELETE /users/{id}
        // itself returns 204) — nothing listens here, so PaymentServiceClient's
        // cascade call fails silently, same as UserDeleteCascadeFailureIntegrationTest.
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
    void createDeleteRecreateConflict() {
        String email = "test@example.com";

        // Bystander account, used only to obtain a valid Bearer token for the
        // now-protected GET /users/{id} and DELETE /users/{id} calls below.
        HttpEntity<Void> authEntity = accounts.newAdminEntity();

        // Step 1 — POST /users → 201 Created
        Map<String, String> body = Map.of("email", email, "password", "test_password_1");
        ResponseEntity<Map> createResponse = restTemplate.postForEntity("/users", body, Map.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).containsKey("id");
        assertThat(createResponse.getBody()).containsEntry("email", email);

        String userId = (String) createResponse.getBody().get("id");
        UUID id = UUID.fromString(userId);

        // Step 2 — DELETE /users/{id} → 204 No Content
        ResponseEntity<Void> deleteResponse =
                restTemplate.exchange("/users/" + id, HttpMethod.DELETE, authEntity, Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Step 3 — GET /users/{id} → 404 (soft-deleted)
        ResponseEntity<Map> getAfterDelete =
                restTemplate.exchange("/users/" + id, HttpMethod.GET, authEntity, Map.class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getAfterDelete.getBody()).containsEntry("status", 404);

        // Step 4 — POST /users same email → 201 (partial index released the slot)
        ResponseEntity<Map> recreateResponse = restTemplate.postForEntity("/users", body, Map.class);
        assertThat(recreateResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(recreateResponse.getBody()).containsEntry("email", email);

        // The new user must have a different id than the soft-deleted one
        String newUserId = (String) recreateResponse.getBody().get("id");
        assertThat(UUID.fromString(newUserId)).isNotEqualTo(id);

        // Step 5 — POST /users same email again → 409 (now active again)
        ResponseEntity<Map> conflictResponse = restTemplate.postForEntity("/users", body, Map.class);
        assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictResponse.getBody()).containsEntry("status", 409);
    }
}
