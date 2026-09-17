package com.travelplan.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for POST /login.
 *
 * The property under test that matters most for this endpoint: an unknown
 * email and a wrong password for a known email must be indistinguishable to
 * the caller (same HTTP status, same body). A successful login returns
 * {id, email, token} — JWT issuance/validation is covered separately by
 * {@link JwtIntegrationTest}.
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthIntegrationTest {

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
        // Test-only secret, >= 32 bytes (256 bits) as required by HS256 (jjwt
        // throws WeakKeyException otherwise). Never used outside this test JVM.
        registry.add("JWT_SIGNING_KEY", () -> "test-only-signing-key-must-be-at-least-32-bytes-long");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void loginSucceedsWithCorrectCredentialsAndNeverExposesTheHash() {
        String email = "login-ok@example.com";
        String password = "secret123";
        createUser(email, password);

        // Step 2 — POST /login correct password → 200
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/login", Map.of("email", email, "password", password), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("id");
        assertThat(response.getBody()).containsEntry("email", email);
        assertThat(response.getBody()).containsKey("token");
        assertThat((String) response.getBody().get("token")).isNotBlank();
        assertThat(response.getBody()).doesNotContainKeys("password", "password_hash", "passwordHash");
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnTheExactSameResponse() {
        String email = "login-wrongpw@example.com";
        createUser(email, "secret123");

        // Step 3 — POST /login wrong password for a known email → 401
        ResponseEntity<Map> wrongPassword = restTemplate.postForEntity(
                "/login", Map.of("email", email, "password", "not-the-right-one"), Map.class);

        // Step 4 — POST /login unknown email → 401, same body
        ResponseEntity<Map> unknownEmail = restTemplate.postForEntity(
                "/login", Map.of("email", "does-not-exist@example.com", "password", "not-the-right-one"), Map.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).isEqualTo(unknownEmail.getBody());
        assertThat(wrongPassword.getBody()).containsEntry("error", "Invalid credentials");
    }

    @Test
    void noResponseEverExposesThePasswordHash() {
        String email = "no-leak@example.com";

        // Step 1 — POST /users → 201, response must not leak the hash
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                "/users", Map.of("email", email, "password", "secret123"), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).doesNotContainKeys("password", "password_hash", "passwordHash");

        // Step 5 — a 400 validation error must not leak anything either
        ResponseEntity<Map> badRequest = restTemplate.postForEntity(
                "/users", Map.of("email", "not-an-email", "password", "short"), Map.class);
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRequest.getBody()).doesNotContainKeys("password", "password_hash", "passwordHash");

        // A 401 login failure must not leak anything either
        ResponseEntity<Map> loginFailure = restTemplate.postForEntity(
                "/login", Map.of("email", email, "password", "wrong"), Map.class);
        assertThat(loginFailure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(loginFailure.getBody()).doesNotContainKeys("password", "password_hash", "passwordHash");
    }

    private void createUser(String email, String password) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/users", Map.of("email", email, "password", password), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}