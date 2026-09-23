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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the role-and-ownership RBAC introduced in
 * docs/lets-travel-architecture-decisions.md §1: a TRAVELER/TRAVEL_MANAGER
 * may create/read/delete their own payments, an ADMIN may act on any
 * payment, and no one may act on someone else's payment.
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentRbacOwnershipIntegrationTest {

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

    private HttpHeaders headersFor(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void travelerCanCreateAndReadTheirOwnPayment() {
        UUID travelerId = UUID.randomUUID();
        String token = TestJwtTokens.tokenFor(travelerId, "TRAVELER");

        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", travelerId, "amount", 42.50, "currency", "EUR"), headersFor(token)),
                Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String paymentId = (String) createResponse.getBody().get("id");

        ResponseEntity<Map> getResponse = restTemplate.exchange(
                "/payments/" + paymentId, HttpMethod.GET, new HttpEntity<>(headersFor(token)), Map.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void travelerCannotCreateAPaymentForAnotherUser() {
        UUID travelerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        String token = TestJwtTokens.tokenFor(travelerId, "TRAVELER");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", otherUserId, "amount", 10, "currency", "EUR"), headersFor(token)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Not allowed to act on another user's payment");
    }

    @Test
    void travelerCannotReadAnotherTravelersPayment() {
        UUID ownerId = UUID.randomUUID();
        String ownerToken = TestJwtTokens.tokenFor(ownerId, "TRAVELER");
        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", ownerId, "amount", 15, "currency", "EUR"), headersFor(ownerToken)),
                Map.class);
        String paymentId = (String) createResponse.getBody().get("id");

        String otherTravelerToken = TestJwtTokens.tokenFor(UUID.randomUUID(), "TRAVELER");
        ResponseEntity<Map> getResponse = restTemplate.exchange(
                "/payments/" + paymentId, HttpMethod.GET, new HttpEntity<>(headersFor(otherTravelerToken)), Map.class);

        // Masked as 404, not 403 — must not leak that the payment exists (see PaymentService#findById).
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listPaymentsIsScopedToTheCallerUnlessAdmin() {
        UUID travelerId = UUID.randomUUID();
        String travelerToken = TestJwtTokens.tokenFor(travelerId, "TRAVELER");
        restTemplate.exchange("/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", travelerId, "amount", 20, "currency", "EUR"), headersFor(travelerToken)),
                Map.class);

        UUID otherTravelerId = UUID.randomUUID();
        String otherToken = TestJwtTokens.tokenFor(otherTravelerId, "TRAVELER");
        restTemplate.exchange("/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", otherTravelerId, "amount", 30, "currency", "EUR"), headersFor(otherToken)),
                Map.class);

        ResponseEntity<List> travelerListResponse = restTemplate.exchange(
                "/payments", HttpMethod.GET, new HttpEntity<>(headersFor(travelerToken)), List.class);
        assertThat(travelerListResponse.getBody()).isNotEmpty().allSatisfy(
                p -> assertThat((Map<String, Object>) p).containsEntry("userId", travelerId.toString()));

        String adminToken = TestJwtTokens.tokenFor(UUID.randomUUID(), "ADMIN");
        ResponseEntity<List> adminListResponse = restTemplate.exchange(
                "/payments", HttpMethod.GET, new HttpEntity<>(headersFor(adminToken)), List.class);
        assertThat(adminListResponse.getBody()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void travelManagerActsAsATravelerOnTheirOwnPayments() {
        UUID managerId = UUID.randomUUID();
        String token = TestJwtTokens.tokenFor(managerId, "TRAVEL_MANAGER");

        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", managerId, "amount", 5, "currency", "EUR"), headersFor(token)),
                Map.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void onlyAdminCanForceAPaymentsStatus() {
        UUID travelerId = UUID.randomUUID();
        String travelerToken = TestJwtTokens.tokenFor(travelerId, "TRAVELER");
        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", travelerId, "amount", 8, "currency", "EUR"), headersFor(travelerToken)),
                Map.class);
        String paymentId = (String) createResponse.getBody().get("id");

        ResponseEntity<Map> selfAttempt = restTemplate.exchange(
                "/payments/" + paymentId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "COMPLETED"), headersFor(travelerToken)), Map.class);
        assertThat(selfAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String adminToken = TestJwtTokens.tokenFor(UUID.randomUUID(), "ADMIN");
        ResponseEntity<Map> adminAttempt = restTemplate.exchange(
                "/payments/" + paymentId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "COMPLETED"), headersFor(adminToken)), Map.class);
        assertThat(adminAttempt.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
