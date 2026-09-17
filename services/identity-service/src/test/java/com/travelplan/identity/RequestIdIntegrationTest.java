package com.travelplan.identity;

import com.travelplan.identity.filter.RequestIdFilter;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link RequestIdFilter}, exercised end-to-end through
 * a real HTTP round-trip (not a unit test of the filter in isolation), on
 * the health endpoint (unauthenticated, so no token setup is needed here —
 * this test is only about the {@code X-Request-Id} header, not auth).
 *
 * <p>Model reusable as-is (same filter class name, same header/MDC
 * convention) for payment-service and travel-service.</p>
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class RequestIdIntegrationTest {

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
    void requestWithoutHeaderReceivesAGeneratedRequestId() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String requestId = response.getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(requestId).isNotBlank();
        // Generated value must be a valid UUID.
        assertThat(UUID.fromString(requestId)).isNotNull();
    }

    @Test
    void requestWithHeaderGetsTheSameRequestIdBackUnchanged() {
        String suppliedRequestId = UUID.randomUUID().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.add(RequestIdFilter.REQUEST_ID_HEADER, suppliedRequestId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER))
                .isEqualTo(suppliedRequestId);
    }
}
