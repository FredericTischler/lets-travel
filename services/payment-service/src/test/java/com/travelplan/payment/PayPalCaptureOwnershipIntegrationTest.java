package com.travelplan.payment;

import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.controllers.OrdersController;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderStatus;
import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;
import com.travelplan.payment.repository.PaymentRepository;
import com.travelplan.payment.support.TestJwtTokens;
import com.travelplan.payment.support.TestProviderCredentials;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security audit G2: {@code POST /payments/paypal/{orderId}/capture} is
 * ownership-aware. Real PostgreSQL (Testcontainers), PayPal SDK client mocked
 * (no sandbox credentials needed, unlike {@link PayPalCaptureIntegrationTest}):
 * what is proven here is the authorization decision and that a non-owner never
 * reaches PayPal, not PayPal's own behaviour.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class PayPalCaptureOwnershipIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.5-bookworm")
                    .withDatabaseName("payment_db")
                    .withUsername("payment_user")
                    .withPassword("test_password_only");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
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

    @MockitoBean
    private PaypalServerSdkClient paypalClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    private OrdersController orders;

    @BeforeEach
    void stubPayPal() throws Exception {
        reset(paypalClient);
        orders = mock(OrdersController.class);
        when(paypalClient.getOrdersController()).thenReturn(orders);
        Order completed = new Order();
        completed.setStatus(OrderStatus.COMPLETED);
        when(orders.captureOrder(any())).thenReturn(new ApiResponse<>(201, null, completed));
    }

    @Test
    void ownerCanCaptureTheirOwnOrder() {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrderOf(owner);

        ResponseEntity<Map> response = capture(TestJwtTokens.tokenFor(owner, "TRAVELER"), orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
    }

    @Test
    void anotherUserGetsTheSame404AsForAnUnknownOrderAndPayPalIsNeverCalled() throws Exception {
        String orderId = pendingOrderOf(UUID.randomUUID());
        String intruder = TestJwtTokens.tokenFor(UUID.randomUUID(), "TRAVELER");

        ResponseEntity<Map> foreign = capture(intruder, orderId);
        ResponseEntity<Map> unknown = capture(intruder, "NO-SUCH-ORDER-" + UUID.randomUUID());

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Same status and same body shape: nothing tells a foreign order from a missing one.
        assertThat(foreign.getBody().keySet()).isEqualTo(unknown.getBody().keySet());
        verify(orders, never()).captureOrder(any());
        // ...and the victim's payment is untouched (still capturable by its owner).
        assertThat(paymentRepository.findActiveByExternalReference(orderId).orElseThrow().getStatus())
                .isEqualTo(Payment.STATUS_PENDING);
    }

    @Test
    void aTravelManagerIsNotAnAdminEither() {
        String orderId = pendingOrderOf(UUID.randomUUID());

        ResponseEntity<Map> response = capture(TestJwtTokens.tokenFor(UUID.randomUUID(), "TRAVEL_MANAGER"), orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anAdminCanCaptureAnyonesOrder() {
        String orderId = pendingOrderOf(UUID.randomUUID());

        ResponseEntity<Map> response = capture(TestJwtTokens.validToken(), orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
    }

    @Test
    void anUnknownOrderIsA404ForAnAdminToo() {
        ResponseEntity<Map> response = capture(TestJwtTokens.validToken(), "NO-SUCH-ORDER-" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void withoutATokenTheEndpointIsStill401() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/payments/paypal/whatever/capture", new HttpEntity<>(null, new HttpHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String pendingOrderOf(UUID userId) {
        String orderId = "ORDER-" + UUID.randomUUID();
        paymentRepository.save(new Payment(userId, new BigDecimal("19.99"), "USD", PaymentProvider.PAYPAL, orderId));
        return orderId;
    }

    private ResponseEntity<Map> capture(String token, String orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(
                "/payments/paypal/" + orderId + "/capture", new HttpEntity<>(null, headers), Map.class);
    }
}
