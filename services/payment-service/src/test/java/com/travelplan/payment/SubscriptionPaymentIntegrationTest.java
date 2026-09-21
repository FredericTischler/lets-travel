package com.travelplan.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.net.Webhook;
import com.sun.net.httpserver.HttpServer;
import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;
import com.travelplan.payment.repository.PaymentRepository;
import com.travelplan.payment.support.TestJwtTokens;
import com.travelplan.payment.support.TestProviderCredentials;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
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

import javax.crypto.SecretKey;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for payment-service's side of docs/lets-travel-architecture-decisions.md
 * §4 (paying for a subscription):
 * <ul>
 *   <li>a payment can be linked to a subscription ({@code travelId} +
 *       {@code subscriptionRef}), and only for its owner;</li>
 *   <li>when such a payment reaches {@code COMPLETED}/{@code FAILED} — via the
 *       admin PATCH or the Stripe webhook — payment-service calls travel-service
 *       with a service token; a failed call never fails the status change and
 *       is re-sent by {@code POST /payments/reconcile-subscriptions};</li>
 *   <li>{@code GET /payments/summary} (traveler stats: preferred payment methods).</li>
 * </ul>
 *
 * travel-service is stubbed with a plain JDK {@link HttpServer} — the same
 * technique as identity-service's {@code UserDeleteCascadeIntegrationTest} —
 * so the real {@code TravelServiceClient}/{@code RestClient} runs over a real
 * socket. Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class SubscriptionPaymentIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One request received by the stubbed travel-service. */
    private record Received(String path, String authorization, String requestId, JsonNode body) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    /** HTTP status the stub answers with; flipped to 500 to simulate a travel-service failure. */
    private static volatile int stubStatus = 200;

    // Started eagerly (static initializer), not @BeforeAll: it must be listening before Spring
    // resolves the lazy @DynamicPropertySource supplier that references its port.
    private static final HttpServer STUB_TRAVEL_SERVICE = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/subscriptions/", exchange -> {
                byte[] raw = exchange.getRequestBody().readAllBytes();
                RECEIVED.add(new Received(
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("X-Request-Id"),
                        JSON.readTree(new String(raw, StandardCharsets.UTF_8))));
                exchange.sendResponseHeaders(stubStatus, -1);
                exchange.close();
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start stub travel-service", ex);
        }
    }

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
        registry.add("TRAVEL_SERVICE_URL",
                () -> "http://localhost:" + STUB_TRAVEL_SERVICE.getAddress().getPort());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @BeforeEach
    void resetStub() {
        stubStatus = 200;
    }

    // ---------------------------------------------------------------- linking + ownership

    @Test
    void travelerCanCreateAPaymentLinkedToTheirOwnSubscription() {
        UUID travelerId = UUID.randomUUID();
        UUID travelId = UUID.randomUUID();
        UUID subscriptionRef = UUID.randomUUID();

        ResponseEntity<Map> response = createLinkedManualPayment(travelerId, travelId, subscriptionRef, "100.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "PENDING");
        assertThat(response.getBody()).containsEntry("travelId", travelId.toString());
        assertThat(response.getBody()).containsEntry("subscriptionRef", subscriptionRef.toString());
        // Still PENDING: nothing to report to travel-service yet.
        assertThat(receivedFor(subscriptionRef)).isEmpty();
    }

    @Test
    void travelerCannotCreateALinkedPaymentOnBehalfOfAnotherUser() {
        UUID traveler = UUID.randomUUID();
        UUID victim = UUID.randomUUID();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments", HttpMethod.POST,
                new HttpEntity<>(linkedBody(victim, UUID.randomUUID(), UUID.randomUUID(), "100.00"),
                        headersFor(TestJwtTokens.tokenFor(traveler, "TRAVELER"))),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aHalfLinkedPaymentIsRejected() {
        UUID traveler = UUID.randomUUID();
        Map<String, Object> body = Map.of("userId", traveler, "amount", "10.00", "currency", "EUR",
                "subscriptionRef", UUID.randomUUID());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments", HttpMethod.POST,
                new HttpEntity<>(body, headersFor(TestJwtTokens.tokenFor(traveler, "TRAVELER"))), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- COMPLETED / FAILED -> travel-service

    @Test
    void completingALinkedPaymentConfirmsItToTravelServiceWithAServiceToken() {
        UUID travelerId = UUID.randomUUID();
        UUID travelId = UUID.randomUUID();
        UUID subscriptionRef = UUID.randomUUID();
        UUID paymentId = paymentIdOf(createLinkedManualPayment(travelerId, travelId, subscriptionRef, "100.00"));

        ResponseEntity<Map> patch = setStatus(paymentId, "COMPLETED");
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Received> calls = receivedFor(subscriptionRef);
        assertThat(calls).hasSize(1);
        Received call = calls.get(0);
        assertThat(call.path()).isEqualTo("/internal/subscriptions/" + subscriptionRef + "/payment-result");
        assertThat(call.requestId()).as("X-Request-Id is propagated").isNotBlank();
        assertThat(call.body().get("status").asText()).isEqualTo("COMPLETED");
        assertThat(call.body().get("userId").asText()).isEqualTo(travelerId.toString());
        assertThat(call.body().get("travelId").asText()).isEqualTo(travelId.toString());
        assertThat(call.body().get("paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(call.body().get("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(call.body().get("currency").asText()).isEqualTo("EUR");

        Claims claims = parseServiceToken(call.authorization());
        assertThat(claims.getSubject()).isEqualTo("service:payment");
        assertThat(claims.get("role")).as("a service token carries no user role").isNull();

        assertThat(paymentRepository.findActiveById(paymentId).orElseThrow().getTravelNotifiedAt()).isNotNull();
    }

    @Test
    void failingALinkedPaymentReportsFailedToTravelService() {
        UUID subscriptionRef = UUID.randomUUID();
        UUID paymentId = paymentIdOf(createLinkedManualPayment(
                UUID.randomUUID(), UUID.randomUUID(), subscriptionRef, "55.00"));

        assertThat(setStatus(paymentId, "FAILED").getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Received> calls = receivedFor(subscriptionRef);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).body().get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void aPaymentWithoutSubscriptionNeverCallsTravelService() {
        UUID travelerId = UUID.randomUUID();
        int before = RECEIVED.size();
        ResponseEntity<Map> created = restTemplate.exchange(
                "/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", travelerId, "amount", "12.00", "currency", "EUR"),
                        headersFor(TestJwtTokens.tokenFor(travelerId, "TRAVELER"))),
                Map.class);

        assertThat(setStatus(paymentIdOf(created), "COMPLETED").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(RECEIVED).hasSize(before);
    }

    @Test
    void stripeWebhookOnALinkedPaymentConfirmsItToTravelService() {
        UUID subscriptionRef = UUID.randomUUID();
        String paymentIntentId = "pi_sub_" + UUID.randomUUID();
        Payment payment = new Payment(UUID.randomUUID(), new BigDecimal("80.00"), "EUR",
                PaymentProvider.STRIPE, paymentIntentId);
        payment.linkToSubscription(UUID.randomUUID(), subscriptionRef);
        paymentRepository.save(payment);

        String payload = "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"object\":\"event\","
                + "\"api_version\":\"2020-08-27\",\"created\":" + Instant.now().getEpochSecond() + ","
                + "\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"" + paymentIntentId
                + "\",\"object\":\"payment_intent\",\"amount\":8000,\"currency\":\"eur\",\"status\":\"succeeded\"}}}";
        long timestamp = Instant.now().getEpochSecond();
        String signature;
        try {
            signature = Webhook.Util.computeHmacSha256(
                    TestProviderCredentials.STRIPE_WEBHOOK_SECRET, timestamp + "." + payload);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Stripe-Signature", "t=" + timestamp + ",v1=" + signature);

        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/webhooks/stripe", new HttpEntity<>(payload, headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Received> calls = receivedFor(subscriptionRef);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).body().get("status").asText()).isEqualTo("COMPLETED");
    }

    // ---------------------------------------------------------------- failure window + reconciliation

    @Test
    void aFailedConfirmationNeverFailsTheStatusChangeAndIsRetriedByReconciliation() {
        UUID subscriptionRef = UUID.randomUUID();
        UUID paymentId = paymentIdOf(createLinkedManualPayment(
                UUID.randomUUID(), UUID.randomUUID(), subscriptionRef, "100.00"));

        // travel-service answers 500: the payment still completes, the call is just not acknowledged.
        stubStatus = 500;
        ResponseEntity<Map> patch = setStatus(paymentId, "COMPLETED");
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patch.getBody()).containsEntry("status", "COMPLETED");
        assertThat(receivedFor(subscriptionRef)).hasSize(1);
        assertThat(paymentRepository.findActiveById(paymentId).orElseThrow().getTravelNotifiedAt()).isNull();

        // Recovery: reconciliation re-sends it.
        stubStatus = 200;
        ResponseEntity<Map> reconcile = restTemplate.exchange(
                "/payments/reconcile-subscriptions", HttpMethod.POST,
                new HttpEntity<>(headersFor(TestJwtTokens.validToken())), Map.class);

        assertThat(reconcile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) reconcile.getBody().get("notified")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(receivedFor(subscriptionRef)).hasSize(2);
        assertThat(paymentRepository.findActiveById(paymentId).orElseThrow().getTravelNotifiedAt()).isNotNull();

        // Nothing left to reconcile for this payment: a second pass does not re-send it.
        restTemplate.exchange("/payments/reconcile-subscriptions", HttpMethod.POST,
                new HttpEntity<>(headersFor(TestJwtTokens.validToken())), Map.class);
        assertThat(receivedFor(subscriptionRef)).hasSize(2);
    }

    @Test
    void onlyAnAdminMayTriggerReconciliation() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments/reconcile-subscriptions", HttpMethod.POST,
                new HttpEntity<>(headersFor(TestJwtTokens.tokenFor(UUID.randomUUID(), "TRAVELER"))), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------- summary

    @Test
    @SuppressWarnings("unchecked")
    void summaryCountsCompletedPaymentsPerProviderAndPicksTheMostUsed() {
        UUID travelerId = UUID.randomUUID();
        savePayment(travelerId, "10.00", "EUR", PaymentProvider.MANUAL, Payment.STATUS_COMPLETED);
        savePayment(travelerId, "15.50", "EUR", PaymentProvider.MANUAL, Payment.STATUS_COMPLETED);
        savePayment(travelerId, "40.00", "EUR", PaymentProvider.STRIPE, Payment.STATUS_COMPLETED);
        savePayment(travelerId, "7.00", "USD", PaymentProvider.STRIPE, Payment.STATUS_COMPLETED);
        savePayment(travelerId, "99.00", "EUR", PaymentProvider.PAYPAL, Payment.STATUS_FAILED);
        savePayment(travelerId, "99.00", "EUR", PaymentProvider.PAYPAL, Payment.STATUS_PENDING);
        // Someone else's payment must not leak into this traveler's summary.
        savePayment(UUID.randomUUID(), "500.00", "EUR", PaymentProvider.PAYPAL, Payment.STATUS_COMPLETED);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments/summary", HttpMethod.GET,
                new HttpEntity<>(headersFor(TestJwtTokens.tokenFor(travelerId, "TRAVELER"))), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("userId", travelerId.toString());
        assertThat(((Number) body.get("totalCount")).intValue()).isEqualTo(4);
        // MANUAL and STRIPE both have 2 completed payments: the tie is broken by provider name.
        assertThat(body).containsEntry("mostUsedProvider", "MANUAL");
        List<Map<String, Object>> byProvider = (List<Map<String, Object>>) body.get("byProvider");
        assertThat(byProvider).extracting(m -> m.get("provider")).containsExactly("MANUAL", "STRIPE");
        Map<String, Object> manual = byProvider.get(0);
        assertThat(((Number) manual.get("count")).intValue()).isEqualTo(2);
        assertThat(new BigDecimal(((Map<String, Object>) manual.get("totals")).get("EUR").toString()))
                .isEqualByComparingTo("25.50");
        Map<String, Object> stripe = byProvider.get(1);
        assertThat(((Number) stripe.get("count")).intValue()).isEqualTo(2);
        assertThat(((Map<String, Object>) stripe.get("totals")).keySet()).containsExactlyInAnyOrder("EUR", "USD");
    }

    @Test
    void summaryOfAUserWithNoCompletedPaymentIsEmpty() {
        UUID travelerId = UUID.randomUUID();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments/summary", HttpMethod.GET,
                new HttpEntity<>(headersFor(TestJwtTokens.tokenFor(travelerId, "TRAVELER"))), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("totalCount")).intValue()).isZero();
        assertThat((List<?>) response.getBody().get("byProvider")).isEmpty();
        assertThat(response.getBody().get("mostUsedProvider")).isNull();
    }

    @Test
    void aTravelerCannotReadAnotherUsersSummaryButAnAdminCan() {
        UUID owner = UUID.randomUUID();
        savePayment(owner, "20.00", "EUR", PaymentProvider.PAYPAL, Payment.STATUS_COMPLETED);

        ResponseEntity<Map> asOtherTraveler = restTemplate.exchange(
                "/payments/summary?userId=" + owner, HttpMethod.GET,
                new HttpEntity<>(headersFor(TestJwtTokens.tokenFor(UUID.randomUUID(), "TRAVELER"))), Map.class);
        assertThat(asOtherTraveler.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> asAdmin = restTemplate.exchange(
                "/payments/summary?userId=" + owner, HttpMethod.GET,
                new HttpEntity<>(headersFor(TestJwtTokens.validToken())), Map.class);
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asAdmin.getBody()).containsEntry("mostUsedProvider", "PAYPAL");

        // Passing one's own id explicitly is fine for a non-admin.
        ResponseEntity<Map> asOwner = restTemplate.exchange(
                "/payments/summary?userId=" + owner, HttpMethod.GET,
                new HttpEntity<>(headersFor(TestJwtTokens.tokenFor(owner, "TRAVELER"))), Map.class);
        assertThat(asOwner.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void summaryRequiresAValidToken() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/payments/summary", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------- helpers

    private void savePayment(UUID userId, String amount, String currency, PaymentProvider provider, String status) {
        Payment payment = new Payment(userId, new BigDecimal(amount), currency, provider, null);
        payment.setStatus(status);
        paymentRepository.save(payment);
    }

    private Map<String, Object> linkedBody(UUID userId, UUID travelId, UUID subscriptionRef, String amount) {
        return Map.of("userId", userId, "amount", amount, "currency", "EUR",
                "travelId", travelId, "subscriptionRef", subscriptionRef);
    }

    private ResponseEntity<Map> createLinkedManualPayment(UUID travelerId, UUID travelId, UUID subscriptionRef,
                                                          String amount) {
        return restTemplate.exchange(
                "/payments", HttpMethod.POST,
                new HttpEntity<>(linkedBody(travelerId, travelId, subscriptionRef, amount),
                        headersFor(TestJwtTokens.tokenFor(travelerId, "TRAVELER"))),
                Map.class);
    }

    private ResponseEntity<Map> setStatus(UUID paymentId, String status) {
        return restTemplate.exchange(
                "/payments/" + paymentId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", status), headersFor(TestJwtTokens.validToken())), Map.class);
    }

    private static UUID paymentIdOf(ResponseEntity<Map> created) {
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private static List<Received> receivedFor(UUID subscriptionRef) {
        return RECEIVED.stream()
                .filter(r -> r.path().contains(subscriptionRef.toString()))
                .toList();
    }

    private static Claims parseServiceToken(String authorizationHeader) {
        assertThat(authorizationHeader).startsWith("Bearer ");
        SecretKey key = Keys.hmacShaKeyFor(TestJwtTokens.SIGNING_KEY.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(authorizationHeader.substring("Bearer ".length())).getPayload();
    }

    private static HttpHeaders headersFor(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
