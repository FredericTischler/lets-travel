package com.travelplan.payment;

import com.stripe.net.Webhook;
import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;
import com.travelplan.payment.repository.PaymentRepository;
import com.travelplan.payment.support.TestJwtTokens;
import com.travelplan.payment.support.TestProviderCredentials;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@code POST /webhooks/stripe} (docs/sujet.md §2).
 *
 * <p>Unlike {@link StripePaymentIntegrationTest}, this test does NOT need a
 * real Stripe API key: signature verification and event processing are both
 * purely local (HMAC-SHA256 over the raw payload, no network call to
 * Stripe), so it runs unconditionally, exercising the real
 * {@code com.stripe.net.Webhook.constructEvent} verification logic (via
 * {@link Webhook.Util#computeHmacSha256}, the exact same algorithm the SDK
 * itself uses to compute and check the {@code Stripe-Signature} header) —
 * not a hand-rolled mock of it.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class StripeWebhookIntegrationTest {

    private static final String WEBHOOK_SECRET = TestProviderCredentials.STRIPE_WEBHOOK_SECRET;

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
        registry.add("PAYPAL_CLIENT_ID", () -> TestProviderCredentials.PAYPAL_CLIENT_ID);
        registry.add("PAYPAL_CLIENT_SECRET", () -> TestProviderCredentials.PAYPAL_CLIENT_SECRET);
        registry.add("STRIPE_API_KEY", () -> TestProviderCredentials.STRIPE_API_KEY);
        registry.add("STRIPE_SECRET_KEY", () -> TestProviderCredentials.STRIPE_SECRET_KEY);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> WEBHOOK_SECRET);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    private static String eventPayload(String paymentIntentId, String eventType) {
        return "{"
                + "\"id\":\"evt_test_" + UUID.randomUUID() + "\","
                + "\"object\":\"event\","
                + "\"api_version\":\"2020-08-27\","
                + "\"created\":" + Instant.now().getEpochSecond() + ","
                + "\"type\":\"" + eventType + "\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"" + paymentIntentId + "\","
                + "\"object\":\"payment_intent\","
                + "\"amount\":1999,"
                + "\"currency\":\"usd\","
                + "\"status\":\"succeeded\""
                + "}}"
                + "}";
    }

    private static String signatureHeader(String payload, String secret) {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        try {
            String signature = Webhook.Util.computeHmacSha256(secret, signedPayload);
            return "t=" + timestamp + ",v1=" + signature;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private HttpEntity<String> requestWithSignature(String payload, String sigHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Stripe-Signature", sigHeader);
        return new HttpEntity<>(payload, headers);
    }

    @Test
    void rejectsInvalidSignature() {
        String payload = eventPayload("pi_does_not_matter", "payment_intent.succeeded");
        HttpEntity<String> request = requestWithSignature(payload, "t=1,v1=deadbeefnotarealsignature");

        ResponseEntity<Map> response = restTemplate.postForEntity("/webhooks/stripe", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void paymentIntentSucceededTransitionsPaymentToCompleted() {
        String paymentIntentId = "pi_test_" + UUID.randomUUID();
        Payment payment = new Payment(UUID.randomUUID(), new BigDecimal("19.99"), "USD",
                PaymentProvider.STRIPE, paymentIntentId);
        Payment saved = paymentRepository.save(payment);

        String payload = eventPayload(paymentIntentId, "payment_intent.succeeded");
        HttpEntity<String> request = requestWithSignature(payload, signatureHeader(payload, WEBHOOK_SECRET));

        ResponseEntity<Void> response = restTemplate.postForEntity("/webhooks/stripe", request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paymentRepository.findActiveById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(Payment.STATUS_COMPLETED);
    }

    @Test
    void paymentIntentPaymentFailedTransitionsPaymentToFailed() {
        String paymentIntentId = "pi_test_" + UUID.randomUUID();
        Payment payment = new Payment(UUID.randomUUID(), new BigDecimal("19.99"), "USD",
                PaymentProvider.STRIPE, paymentIntentId);
        Payment saved = paymentRepository.save(payment);

        String payload = eventPayload(paymentIntentId, "payment_intent.payment_failed");
        HttpEntity<String> request = requestWithSignature(payload, signatureHeader(payload, WEBHOOK_SECRET));

        ResponseEntity<Void> response = restTemplate.postForEntity("/webhooks/stripe", request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paymentRepository.findActiveById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(Payment.STATUS_FAILED);
    }

    @Test
    void unknownExternalReferenceIsAcceptedAndIgnored() {
        String payload = eventPayload("pi_never_created_" + UUID.randomUUID(), "payment_intent.succeeded");
        HttpEntity<String> request = requestWithSignature(payload, signatureHeader(payload, WEBHOOK_SECRET));

        ResponseEntity<Void> response = restTemplate.postForEntity("/webhooks/stripe", request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
