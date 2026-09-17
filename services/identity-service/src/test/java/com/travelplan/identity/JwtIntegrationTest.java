package com.travelplan.identity;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for JWT issuance on {@code POST /login} and validation on
 * {@code GET /me}.
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class JwtIntegrationTest {

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
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void meReturnsTheUserForAValidToken() {
        String email = "me-ok@example.com";
        String password = "secret123";
        restTemplate.postForEntity("/users", Map.of("email", email, "password", password), Map.class);

        ResponseEntity<Map> loginResponse = restTemplate.postForEntity(
                "/login", Map.of("email", email, "password", password), Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) loginResponse.getBody().get("token");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        ResponseEntity<Map> meResponse = restTemplate.exchange(
                "/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meResponse.getBody()).containsEntry("email", email);
        assertThat(meResponse.getBody()).containsEntry("id", loginResponse.getBody().get("id"));
        assertThat(meResponse.getBody()).doesNotContainKeys("password", "password_hash", "passwordHash");
    }

    @Test
    void meWithoutAuthorizationHeaderReturns401() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/me", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "Invalid or missing token");
    }

    @Test
    void meWithMalformedOrTamperedTokenReturns401WithTheSameMessageAsAMissingHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer not-a-real-jwt");
        ResponseEntity<Map> malformed = restTemplate.exchange(
                "/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        ResponseEntity<Map> missingHeader = restTemplate.getForEntity("/me", Map.class);

        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(malformed.getBody()).isEqualTo(missingHeader.getBody());
    }
}