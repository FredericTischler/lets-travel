package com.travelplan.payment;

import com.travelplan.payment.support.TestJwtTokens;
import com.travelplan.payment.support.TestProviderCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for INCREMENT 1 of user ownership (V2__add_user_id.sql):
 * - {@code userId} on payment creation (present in the response, mandatory).
 * - Bulk soft-delete of every active payment for a given user via
 *   {@code DELETE /payments/by-user/{userId}}, protected by the same Bearer
 *   token mechanism as every other route (see PaymentController javadoc on
 *   that endpoint — no cascade is actually triggered anywhere yet).
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentUserOwnershipIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.5-bookworm")
                    .withDatabaseName("payment_db")
                    .withUsername("payment_user")
                    .withPassword("test_password_only");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", postgres::getHost);
        registry.add("DB_PORT", () -> String.valueOf(postgres.getMappedPort(5432)));
        registry.add("DB_NAME", postgres::getDatabaseName);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("JWT_SIGNING_KEY", () -> TestJwtTokens.SIGNING_KEY);
        registry.add("STRIPE_API_KEY", () -> TestProviderCredentials.STRIPE_API_KEY);
        registry.add("STRIPE_SECRET_KEY", () -> TestProviderCredentials.STRIPE_SECRET_KEY);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> TestProviderCredentials.STRIPE_WEBHOOK_SECRET);
        registry.add("PAYPAL_CLIENT_ID", () -> TestProviderCredentials.PAYPAL_CLIENT_ID);
        registry.add("PAYPAL_CLIENT_SECRET", () -> TestProviderCredentials.PAYPAL_CLIENT_SECRET);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createWithUserIdReturnsUserIdInResponse() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> body = Map.of("userId", userId.toString(), "amount", 12.00, "currency", "EUR");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments", HttpMethod.POST, authorizedJsonEntity(body), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("userId", userId.toString());
    }

    @Test
    void createWithoutUserIdIsRejected() {
        // No "userId" key at all: Jackson deserializes it as null, @NotNull rejects it.
        Map<String, Object> body = Map.of("amount", 12.00, "currency", "EUR");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments", HttpMethod.POST, authorizedJsonEntity(body), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteByUserSoftDeletesOnlyThatUsersActivePayments() {
        UUID targetUserId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        UUID payment1 = createPayment(targetUserId);
        UUID payment2 = createPayment(targetUserId);
        UUID payment3 = createPayment(targetUserId);
        UUID otherUsersPayment = createPayment(otherUserId);

        ResponseEntity<Map> deleteResponse = restTemplate.exchange(
                "/payments/by-user/" + targetUserId, HttpMethod.DELETE, authorizedEntity(), Map.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleteResponse.getBody()).containsEntry("deletedCount", 3);

        assertThat(getPayment(payment1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getPayment(payment2).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getPayment(payment3).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> otherUsersPaymentResponse = getPayment(otherUsersPayment);
        assertThat(otherUsersPaymentResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherUsersPaymentResponse.getBody()).containsEntry("userId", otherUserId.toString());
    }

    @Test
    void deleteByUserWithoutAuthorizationHeaderIsRejected() {
        UUID userId = UUID.randomUUID();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments/by-user/" + userId, HttpMethod.DELETE, new HttpEntity<>(new HttpHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deleteByUserAcceptsServiceToken() {
        UUID userId = UUID.randomUUID();
        UUID paymentId = createPayment(userId);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.serviceToken());
        ResponseEntity<Map> deleteResponse = restTemplate.exchange(
                "/payments/by-user/" + userId, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleteResponse.getBody()).containsEntry("deletedCount", 1);
        assertThat(getPayment(paymentId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void serviceTokenRejectedOnOtherEndpoints() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokens.serviceToken());

        Map<String, Object> body = Map.of("userId", UUID.randomUUID().toString(), "amount", 5.00, "currency", "EUR");
        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/payments", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> getAllResponse = restTemplate.exchange(
                "/payments", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(getAllResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        UUID somePaymentId = createPayment(UUID.randomUUID());
        ResponseEntity<Map> deleteResponse = restTemplate.exchange(
                "/payments/" + somePaymentId, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private UUID createPayment(UUID userId) {
        Map<String, Object> body = Map.of("userId", userId.toString(), "amount", 7.50, "currency", "USD");
        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments", HttpMethod.POST, authorizedJsonEntity(body), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private ResponseEntity<Map> getPayment(UUID id) {
        return restTemplate.exchange("/payments/" + id, HttpMethod.GET, authorizedEntity(), Map.class);
    }

    private static HttpEntity<Map<String, Object>> authorizedJsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokens.validToken());
        return new HttpEntity<>(body, headers);
    }

    private static HttpEntity<Void> authorizedEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.validToken());
        return new HttpEntity<>(headers);
    }
}