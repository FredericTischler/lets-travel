package com.travelplan.payment;

import com.travelplan.payment.support.TestJwtTokens;
import com.travelplan.payment.support.TestProviderCredentials;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
 * Integration test for {@code POST /payments/paypal/{orderId}/capture}
 * (docs/sujet.md §2).
 *
 * <p><b>Requires REAL PayPal sandbox credentials</b> — same constraint as
 * {@link PayPalPaymentIntegrationTest}; SKIPS ITSELF via
 * {@link Assumptions#assumeTrue} unless {@code PAYPAL_TEST_CLIENT_ID} /
 * {@code PAYPAL_TEST_CLIENT_SECRET} are set.</p>
 *
 * <p><b>What this test can and cannot prove, even with real credentials:</b>
 * a real capture succeeding requires a human to approve the order in a
 * browser first (PayPal's hosted approval flow) — impossible to automate
 * here. So this test exercises the order-creation + capture-call wiring
 * end-to-end against PayPal's real sandbox API, then asserts the outcome of
 * capturing an order that was never approved: PayPal's Orders API rejects
 * that as {@code ORDER_NOT_APPROVED}, which this service surfaces as 502
 * (Bad Gateway) with the payment transitioned to {@code FAILED}. This is a
 * real, non-mocked assertion about {@code PayPalPaymentService#captureOrder}'s
 * error-handling path — it does NOT exercise the success path
 * ({@code COMPLETED}), which would require manual browser approval and is
 * therefore not covered by any automated test in this repository.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class PayPalCaptureIntegrationTest {

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
        String realClientId = System.getenv("PAYPAL_TEST_CLIENT_ID");
        String realClientSecret = System.getenv("PAYPAL_TEST_CLIENT_SECRET");
        registry.add("PAYPAL_CLIENT_ID", () -> realClientId != null ? realClientId : TestProviderCredentials.PAYPAL_CLIENT_ID);
        registry.add("PAYPAL_CLIENT_SECRET", () -> realClientSecret != null ? realClientSecret : TestProviderCredentials.PAYPAL_CLIENT_SECRET);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void requireRealPayPalSandboxCredentials() {
        Assumptions.assumeTrue(
                System.getenv("PAYPAL_TEST_CLIENT_ID") != null && System.getenv("PAYPAL_TEST_CLIENT_SECRET") != null,
                "Skipped: requires real PayPal sandbox credentials in the "
                        + "PAYPAL_TEST_CLIENT_ID / PAYPAL_TEST_CLIENT_SECRET environment variables. "
                        + "Not available in this environment — see payment-service final report for details.");
    }

    @Test
    void capturingAnUnapprovedOrderFailsAndMarksPaymentFailed() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.validToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "userId", UUID.randomUUID().toString(),
                "amount", 19.99,
                "currency", "USD");

        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                "/payments/paypal", new HttpEntity<>(body, headers), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = (String) createResponse.getBody().get("orderId");

        HttpHeaders captureHeaders = new HttpHeaders();
        captureHeaders.setBearerAuth(TestJwtTokens.validToken());
        ResponseEntity<Map> captureResponse = restTemplate.postForEntity(
                "/payments/paypal/" + orderId + "/capture",
                new HttpEntity<>(null, captureHeaders), Map.class);

        assertThat(captureResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);

        ResponseEntity<Map> getResponse = restTemplate.exchange(
                "/payments/" + createResponse.getBody().get("id"),
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(null, captureHeaders), Map.class);
        assertThat(getResponse.getBody()).containsEntry("status", "FAILED");
    }
}
