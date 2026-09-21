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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the resilience contract of the cascade delete:
 * if payment-service is unreachable, {@code DELETE /users/{id}} must still
 * return 204 (the user stays deleted, no exception propagates to the
 * client) — see {@code PaymentServiceClient} javadoc for the assumed
 * reconciliation debt this implies.
 *
 * A separate application context from {@link UserDeleteCascadeIntegrationTest}
 * is required here since {@code PAYMENT_SERVICE_URL} differs (points at an
 * address nothing listens on, instead of the stub server).
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class UserDeleteCascadeFailureIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.5-bookworm")
                    .withDatabaseName("identity_db")
                    .withUsername("identity_user")
                    .withPassword("test_password_only");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", postgres::getHost);
        registry.add("DB_PORT", () -> String.valueOf(postgres.getMappedPort(5432)));
        registry.add("DB_NAME", postgres::getDatabaseName);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("JWT_SIGNING_KEY", () -> "test-only-signing-key-must-be-at-least-32-bytes-long");
        // Nothing listens here: exercises the connection-refused branch of
        // PaymentServiceClient's catch(RestClientException) without paying
        // for the full connect-timeout budget.
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
    void deletingAUserStillSucceedsWhenPaymentServiceIsUnreachable() {
        String email = "cascade-resilience-target@example.com";
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                "/users", Map.of("email", email, "password", "secret123"), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String userId = (String) createResponse.getBody().get("id");

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/users/" + userId, HttpMethod.DELETE, accounts.newAdminEntity(), Void.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The user really is deleted despite the cascade failure — no partial
        // rollback, no exception leaked as a 5xx.
        ResponseEntity<Map> getAfterDelete = restTemplate.exchange(
                "/users/" + userId, HttpMethod.GET, accounts.newAdminEntity(), Map.class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
