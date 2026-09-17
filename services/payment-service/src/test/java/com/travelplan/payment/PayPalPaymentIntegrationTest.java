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
 * Integration test for {@code POST /payments/paypal} (docs/sujet.md §2).
 *
 * <p><b>Requires REAL PayPal sandbox credentials.</b> Unlike Stripe, PayPal's
 * Orders API always requires a genuine OAuth client-credentials exchange
 * (client id + secret) even in sandbox — there is no equivalent of Stripe's
 * "test key that just works" without an actual PayPal Developer sandbox app.
 * This environment has no such sandbox app registered, so this test SKIPS
 * ITSELF (via {@link Assumptions#assumeTrue}) unless both
 * {@code PAYPAL_TEST_CLIENT_ID} and {@code PAYPAL_TEST_CLIENT_SECRET} are set
 * to real sandbox credentials when running the build — it does not fabricate
 * a mock that would prove nothing about the real PayPal integration. When it
 * does run, it exercises the actual flow end-to-end against PayPal's real
 * sandbox API: Order creation only — see {@code PayPalPaymentService}
 * javadoc for what is deliberately NOT covered (approval + capture).</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class PayPalPaymentIntegrationTest {

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
        // Real sandbox credentials if provided by the environment, dummy
        // otherwise — enough for the Spring context (and the PayPal client
        // bean, built lazily w.r.t. network calls) to start; the real check
        // happens inside the test body, guarded by assumeTrue below.
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
    void createOrderReturns201WithApproveUrl() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.validToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "userId", UUID.randomUUID().toString(),
                "amount", 19.99,
                "currency", "USD");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/payments/paypal", new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKeys("approveUrl", "orderId");
        assertThat(response.getBody().get("provider")).isEqualTo("PAYPAL");
    }
}
