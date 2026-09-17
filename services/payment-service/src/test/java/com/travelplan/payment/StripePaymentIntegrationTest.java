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
 * Integration test for {@code POST /payments/stripe} (docs/sujet.md §2).
 *
 * <p><b>Requires a REAL Stripe sandbox/test secret key.</b> This environment
 * has no such key available, so this test SKIPS ITSELF (via
 * {@link Assumptions#assumeTrue}) unless the {@code STRIPE_TEST_SECRET_KEY}
 * environment variable is set to a real {@code sk_test_*} key when running
 * the build — it does not fabricate a mock that would prove nothing about
 * the real Stripe integration. When it does run, it exercises the actual
 * flow end-to-end against Stripe's real test-mode API (no fake/mocked HTTP
 * layer): PaymentIntent creation only — see {@code StripePaymentService}
 * javadoc for what is deliberately NOT covered (webhook-based confirmation).</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class StripePaymentIntegrationTest {

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
        // Real key if provided by the environment, dummy otherwise — the
        // dummy value is enough to let the Spring context start (Stripe.apiKey
        // is only exercised, and can legitimately fail, inside the test body
        // itself, which is guarded by assumeTrue below).
        String realKey = System.getenv("STRIPE_TEST_SECRET_KEY");
        registry.add("STRIPE_API_KEY", () -> TestProviderCredentials.STRIPE_API_KEY);
        registry.add("STRIPE_SECRET_KEY", () -> realKey != null ? realKey : TestProviderCredentials.STRIPE_SECRET_KEY);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> TestProviderCredentials.STRIPE_WEBHOOK_SECRET);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void requireRealStripeTestKey() {
        Assumptions.assumeTrue(
                System.getenv("STRIPE_TEST_SECRET_KEY") != null,
                "Skipped: requires a real Stripe sandbox secret key (sk_test_*) in the "
                        + "STRIPE_TEST_SECRET_KEY environment variable. Not available in this "
                        + "environment — see payment-service final report for details.");
    }

    @Test
    void createPaymentIntentReturns201WithClientSecret() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.validToken());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "userId", UUID.randomUUID().toString(),
                "amount", 19.99,
                "currency", "USD");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/payments/stripe", new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKeys("clientSecret", "paymentIntentId");
        assertThat(response.getBody().get("provider")).isEqualTo("STRIPE");
    }
}
