package com.travelplan.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.travelplan.travel.support.TestJwtTokens;
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
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for paying for a subscription
 * (docs/lets-travel-architecture-decisions.md §4 and its Phase 4 addendum):
 * a priced destination starts the subscription as {@code PENDING_PAYMENT},
 * asks payment-service to create the payment, and becomes {@code ACTIVE} /
 * {@code CANCELLED} when payment-service reports {@code COMPLETED} /
 * {@code FAILED} through the internal endpoint; a free destination keeps the
 * direct-{@code ACTIVE} behaviour (covered by {@link SubscriptionIntegrationTest}
 * too, asserted here as well next to its priced counterpart).
 *
 * payment-service is stubbed with a plain JDK {@link HttpServer} — the same
 * technique as identity-service's {@code UserDeleteCascadeIntegrationTest} —
 * so the real {@code PaymentServiceClient}/{@code RestClient} runs over a real
 * socket. The callback direction (payment-service -> travel-service) is
 * exercised by calling the internal endpoint with the same service token
 * payment-service mints. One Testcontainers Neo4j (neo4j:5.26.6-community).
 *
 * The context sets the Stripe/PayPal hold ({@code SUBSCRIPTION_PENDING_TTL_MINUTES})
 * to 0, so a PayPal/Stripe pending subscription is already expired when read —
 * that is how expiry is tested without sleeping — while MANUAL keeps its
 * default 72 h hold and is used by every test that needs a live pending one.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class SubscriptionPaymentIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One request received by the stubbed payment-service. */
    private record Received(String path, String authorization, String requestId, JsonNode body) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    /** HTTP status the stub answers create-payment calls with; 500 simulates payment-service being down. */
    private static volatile int stubStatus = 201;

    // Started eagerly (static initializer), not @BeforeAll: it must be listening before Spring
    // resolves the lazy @DynamicPropertySource supplier that references its port.
    private static final HttpServer STUB_PAYMENT_SERVICE = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/payments", exchange -> {
                byte[] raw = exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                RECEIVED.add(new Received(path,
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("X-Request-Id"),
                        JSON.readTree(new String(raw, StandardCharsets.UTF_8))));
                if (stubStatus != 201) {
                    exchange.sendResponseHeaders(stubStatus, -1);
                    exchange.close();
                    return;
                }
                Map<String, Object> response = new HashMap<>();
                response.put("id", UUID.randomUUID().toString());
                response.put("status", "PENDING");
                if (path.endsWith("/stripe")) {
                    response.put("clientSecret", "pi_test_secret_abc");
                } else if (path.endsWith("/paypal")) {
                    response.put("approveUrl", "https://paypal.test/approve/ORDER-1");
                }
                byte[] out = JSON.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start stub payment-service", ex);
        }
    }

    @Container
    static final Neo4jContainer<?> neo4j =
            new Neo4jContainer<>("neo4j:5.26.6-community")
                    .withAdminPassword("test_password_only");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("NEO4J_HOST", neo4j::getHost);
        registry.add("NEO4J_PORT", () -> String.valueOf(neo4j.getMappedPort(7687)));
        registry.add("NEO4J_USERNAME", () -> "neo4j");
        registry.add("NEO4J_PASSWORD", neo4j::getAdminPassword);
        registry.add("JWT_SIGNING_KEY", () -> TestJwtTokens.SIGNING_KEY);
        registry.add("PAYMENT_SERVICE_URL",
                () -> "http://localhost:" + STUB_PAYMENT_SERVICE.getAddress().getPort());
        // Stripe/PayPal holds expire immediately (see class Javadoc); MANUAL keeps its 72 h default.
        registry.add("SUBSCRIPTION_PENDING_TTL_MINUTES", () -> "0");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void resetStub() {
        stubStatus = 201;
    }

    // ---------------------------------------------------------------- free vs paid

    @Test
    void freeDestinationStaysDirectlyActiveAndNeverCallsPaymentService() {
        UUID destinationId = createDestination(UUID.randomUUID(), BigDecimal.ZERO, 10);
        UUID travelerId = UUID.randomUUID();
        int before = RECEIVED.size();

        ResponseEntity<Map> response = subscribe(destinationId, travelerId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "ACTIVE");
        assertThat(response.getBody()).doesNotContainKey("payment");
        assertThat(RECEIVED).hasSize(before);
    }

    @Test
    void paidDestinationStartsPendingAndCreatesTheLinkedPaymentWithTheTravelersOwnToken() {
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("100.00"), 10);
        UUID travelerId = UUID.randomUUID();
        String travelerToken = TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST,
                jsonEntity(travelerToken, Map.of("provider", "MANUAL")), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("status", "PENDING_PAYMENT")
                .containsEntry("travelerId", travelerId.toString());
        assertThat(body.get("expiresAt")).isNotNull();
        String subscriptionId = (String) body.get("id");
        @SuppressWarnings("unchecked")
        Map<String, Object> payment = (Map<String, Object>) body.get("payment");
        assertThat(payment).containsEntry("provider", "MANUAL");
        assertThat(payment.get("paymentId")).isNotNull();
        assertThat(body).containsEntry("paymentId", payment.get("paymentId"));

        // What travel-service asked payment-service for.
        Received call = receivedFor(subscriptionId).get(0);
        assertThat(call.path()).isEqualTo("/payments");
        assertThat(call.authorization()).as("the traveler's own token is forwarded")
                .isEqualTo("Bearer " + travelerToken);
        assertThat(call.requestId()).as("X-Request-Id is propagated").isNotBlank();
        assertThat(call.body().get("userId").asText()).isEqualTo(travelerId.toString());
        assertThat(call.body().get("travelId").asText()).isEqualTo(destinationId.toString());
        assertThat(call.body().get("subscriptionRef").asText()).isEqualTo(subscriptionId);
        assertThat(call.body().get("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(call.body().get("currency").asText()).isEqualTo("EUR");

        // ...and it shows in the traveler's own history as pending, with the payment attached.
        Map<String, Object> mine = mySubscriptions(travelerToken).get(0);
        assertThat(mine).containsEntry("status", "PENDING_PAYMENT")
                .containsEntry("subscriptionId", subscriptionId)
                .containsEntry("paymentId", payment.get("paymentId"));
    }

    @Test
    void stripeAndPayPalUseTheirOwnPaymentEndpointAndReturnTheirContinuation() {
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("40.00"), 10);

        ResponseEntity<Map> stripe = subscribe(destinationId, UUID.randomUUID(), Map.of("provider", "STRIPE",
                "currency", "USD"));
        ResponseEntity<Map> paypal = subscribe(destinationId, UUID.randomUUID(), Map.of("provider", "PAYPAL"));

        assertThat(stripe.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> stripePayment = (Map<String, Object>) stripe.getBody().get("payment");
        assertThat(stripePayment).containsEntry("clientSecret", "pi_test_secret_abc");
        Received stripeCall = receivedFor((String) stripe.getBody().get("id")).get(0);
        assertThat(stripeCall.path()).isEqualTo("/payments/stripe");
        assertThat(stripeCall.body().get("currency").asText()).isEqualTo("USD");

        assertThat(paypal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> paypalPayment = (Map<String, Object>) paypal.getBody().get("payment");
        assertThat(paypalPayment).containsEntry("approveUrl", "https://paypal.test/approve/ORDER-1");
        assertThat(receivedFor((String) paypal.getBody().get("id")).get(0).path()).isEqualTo("/payments/paypal");
    }

    @Test
    void aPaidDestinationRequiresAProvider() {
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("100.00"), 10);
        UUID travelerId = UUID.randomUUID();
        String token = TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId);

        ResponseEntity<Map> noBody = subscribe(destinationId, travelerId, null);
        ResponseEntity<Map> noProvider = subscribe(destinationId, travelerId, Map.of("currency", "EUR"));

        assertThat(noBody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noProvider.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mySubscriptions(token)).as("nothing was left pending").isEmpty();
    }

    @Test
    void subscribingAgainWhileAPaymentIsPendingIsRejected() {
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("100.00"), 10);
        UUID travelerId = UUID.randomUUID();
        assertThat(subscribeManual(destinationId, travelerId).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(subscribeManual(destinationId, travelerId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void whenPaymentServiceFailsTheSubscriptionIsNotLeftPendingAndTheCallerCanRetry() {
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("100.00"), 1);
        UUID travelerId = UUID.randomUUID();
        String token = TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId);

        stubStatus = 500;
        ResponseEntity<Map> failed = subscribeManual(destinationId, travelerId);
        stubStatus = 201;

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(mySubscriptions(token)).extracting(m -> m.get("status")).containsExactly("CANCELLED");
        // The seat (capacity 1) was released, so the retry succeeds.
        assertThat(subscribeManual(destinationId, travelerId).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---------------------------------------------------------------- COMPLETED / FAILED callbacks

    @Test
    void aCompletedPaymentActivatesThePendingSubscriptionAndReplayIsIdempotent() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createDestination(managerId, new BigDecimal("100.00"), 10);
        UUID travelerId = UUID.randomUUID();
        Map<String, Object> pending = subscribeManual(destinationId, travelerId).getBody();
        String subscriptionId = (String) pending.get("id");
        UUID paymentId = UUID.fromString((String) pending.get("paymentId"));

        ResponseEntity<Map> result = paymentResult(subscriptionId,
                result(destinationId, travelerId, paymentId, "COMPLETED", "100.00", "EUR"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsEntry("status", "ACTIVE");
        Map<String, Object> listed = subscribersOf(destinationId, managerId).get(0);
        assertThat(listed).containsEntry("status", "ACTIVE");
        assertThat(listed.get("expiresAt")).as("an active subscription no longer expires").isNull();

        // At-least-once delivery: a replay changes nothing and still answers 2xx.
        ResponseEntity<Map> replay = paymentResult(subscriptionId,
                result(destinationId, travelerId, paymentId, "COMPLETED", "100.00", "EUR"));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).containsEntry("status", "ACTIVE");
    }

    @Test
    void aFailedPaymentCancelsThePendingSubscriptionAndTheTravelerCanTryAgain() {
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("100.00"), 10);
        UUID travelerId = UUID.randomUUID();
        Map<String, Object> pending = subscribeManual(destinationId, travelerId).getBody();
        UUID paymentId = UUID.fromString((String) pending.get("paymentId"));

        ResponseEntity<Map> result = paymentResult((String) pending.get("id"),
                result(destinationId, travelerId, paymentId, "FAILED", "100.00", "EUR"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsEntry("status", "CANCELLED");
        assertThat(subscribeManual(destinationId, travelerId).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void onlyThePaymentServiceTokenMayReportAPaymentResult() {
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("100.00"), 10);
        UUID travelerId = UUID.randomUUID();
        Map<String, Object> pending = subscribeManual(destinationId, travelerId).getBody();
        String path = "/internal/subscriptions/" + pending.get("id") + "/payment-result";
        Map<String, Object> body = result(destinationId, travelerId,
                UUID.fromString((String) pending.get("paymentId")), "COMPLETED", "100.00", "EUR");

        // The traveler cannot settle their own subscription, nor can any user token, ADMIN included.
        for (String token : List.of(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId),
                TestJwtTokens.tokenWithRole("ADMIN"),
                TestJwtTokens.tokenWithSubject("service:identity"))) {
            ResponseEntity<Map> response = restTemplate.exchange(
                    path, HttpMethod.POST, jsonEntity(token, body), Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        ResponseEntity<Map> anonymous = restTemplate.postForEntity(path, body, Map.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(mySubscriptions(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)))
                .extracting(m -> m.get("status")).containsExactly("PENDING_PAYMENT");
    }

    @Test
    void aPaymentThatDoesNotMatchTheSubscriptionIsRejectedAndChangesNothing() {
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("100.00"), 10);
        UUID travelerId = UUID.randomUUID();
        Map<String, Object> pending = subscribeManual(destinationId, travelerId).getBody();
        String subscriptionId = (String) pending.get("id");
        UUID paymentId = UUID.fromString((String) pending.get("paymentId"));

        // another traveler's payment, another destination's payment, a payment other than the recorded one,
        // a cheap one (the "pay 1 cent, activate a 100 EUR subscription" attack) and the wrong currency
        List<Map<String, Object>> bad = List.of(
                result(destinationId, UUID.randomUUID(), paymentId, "COMPLETED", "100.00", "EUR"),
                result(UUID.randomUUID(), travelerId, paymentId, "COMPLETED", "100.00", "EUR"),
                result(destinationId, travelerId, UUID.randomUUID(), "COMPLETED", "100.00", "EUR"),
                result(destinationId, travelerId, paymentId, "COMPLETED", "0.01", "EUR"),
                result(destinationId, travelerId, paymentId, "COMPLETED", "100.00", "USD"));
        for (Map<String, Object> mismatch : bad) {
            assertThat(paymentResult(subscriptionId, mismatch).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        assertThat(mySubscriptions(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)))
                .extracting(m -> m.get("status")).containsExactly("PENDING_PAYMENT");
    }

    @Test
    void aPaymentResultForAnUnknownSubscriptionIsNotFound() {
        ResponseEntity<Map> response = paymentResult(UUID.randomUUID().toString(),
                result(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "COMPLETED", "10.00", "EUR"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- capacity + expiry

    @Test
    void aPendingPaymentHoldsASeatUntilItFails() {
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("100.00"), 1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<String, Object> pending = subscribeManual(destinationId, first).getBody();

        // The single seat is held by the pending payment.
        assertThat(subscribeManual(destinationId, second).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The payment fails: the seat is released.
        paymentResult((String) pending.get("id"), result(destinationId, first,
                UUID.fromString((String) pending.get("paymentId")), "FAILED", "100.00", "EUR"));
        assertThat(subscribeManual(destinationId, second).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void capacityAlsoBoundsFreeDestinations() {
        UUID destinationId = createDestination(UUID.randomUUID(), BigDecimal.ZERO, 1);

        assertThat(subscribe(destinationId, UUID.randomUUID(), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(subscribe(destinationId, UUID.randomUUID(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anExpiredPendingSubscriptionReadsAsExpiredAndNoLongerHoldsASeat() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createDestination(managerId, new BigDecimal("100.00"), 1);
        UUID abandoning = UUID.randomUUID();
        UUID next = UUID.randomUUID();

        // PAYPAL hold = 0 minutes in this context: already lapsed by the time it is read.
        ResponseEntity<Map> abandoned = subscribe(destinationId, abandoning, Map.of("provider", "PAYPAL"));
        assertThat(abandoned.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(subscribersOf(destinationId, managerId)).extracting(m -> m.get("status"))
                .containsExactly("EXPIRED");
        assertThat(mySubscriptions(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", abandoning)))
                .extracting(m -> m.get("status")).containsExactly("EXPIRED");
        // The capacity-1 seat is free again: an expired hold neither blocks the same traveler
        // from subscribing anew nor another traveler from taking the seat.
        assertThat(subscribe(destinationId, abandoning, Map.of("provider", "PAYPAL")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(subscribeManual(destinationId, next).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aPaymentCompletedAfterTheHoldExpiredStillActivatesTheSubscription() {
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("100.00"), 5);
        UUID travelerId = UUID.randomUUID();
        Map<String, Object> pending = subscribe(destinationId, travelerId, Map.of("provider", "STRIPE")).getBody();
        // (expired at once, see class Javadoc) — the money was taken, so it wins over the expiry.

        ResponseEntity<Map> result = paymentResult((String) pending.get("id"),
                result(destinationId, travelerId, UUID.fromString((String) pending.get("paymentId")),
                        "COMPLETED", "100.00", "EUR"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsEntry("status", "ACTIVE");
    }

    // ---------------------------------------------------------------- cancellation interplay

    @Test
    void aTravelerMayCancelAPendingSubscriptionEvenInsideTheCutoffAndALatePaymentThenNeedsARefund() {
        // Starts tomorrow: an ACTIVE booking could not be cancelled any more, a pending one can.
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("100.00"), 5,
                LocalDate.now().plusDays(1));
        UUID travelerId = UUID.randomUUID();
        String token = TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId);
        Map<String, Object> pending = subscribeManual(destinationId, travelerId).getBody();

        ResponseEntity<Void> cancelled = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The payment nevertheless completes: paid but cancelled -> 409, subscription stays cancelled.
        ResponseEntity<Map> late = paymentResult((String) pending.get("id"),
                result(destinationId, travelerId, UUID.fromString((String) pending.get("paymentId")),
                        "COMPLETED", "100.00", "EUR"));
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mySubscriptions(token)).extracting(m -> m.get("status")).containsExactly("CANCELLED");
    }

    @Test
    void anActiveSubscriptionStillObeysTheThreeDayCutoff() {
        UUID destinationId = createDestination(UUID.randomUUID(), new BigDecimal("100.00"), 5,
                LocalDate.now().plusDays(1));
        UUID travelerId = UUID.randomUUID();
        Map<String, Object> pending = subscribeManual(destinationId, travelerId).getBody();
        paymentResult((String) pending.get("id"),
                result(destinationId, travelerId, UUID.fromString((String) pending.get("paymentId")),
                        "COMPLETED", "100.00", "EUR"));

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.DELETE,
                new HttpEntity<>(bearer(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId))), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<Map> subscribe(UUID destinationId, UUID travelerId, Map<String, Object> body) {
        String token = TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId);
        HttpEntity<?> entity = body == null ? new HttpEntity<>(bearer(token)) : jsonEntity(token, body);
        return restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST, entity, Map.class);
    }

    private ResponseEntity<Map> subscribeManual(UUID destinationId, UUID travelerId) {
        return subscribe(destinationId, travelerId, Map.of("provider", "MANUAL"));
    }

    private static Map<String, Object> result(UUID travelId, UUID userId, UUID paymentId, String status,
                                              String amount, String currency) {
        Map<String, Object> body = new HashMap<>();
        body.put("travelId", travelId);
        body.put("userId", userId);
        body.put("paymentId", paymentId);
        body.put("status", status);
        body.put("amount", new BigDecimal(amount));
        body.put("currency", currency);
        return body;
    }

    private ResponseEntity<Map> paymentResult(String subscriptionRef, Map<String, Object> body) {
        return restTemplate.exchange(
                "/internal/subscriptions/" + subscriptionRef + "/payment-result", HttpMethod.POST,
                jsonEntity(TestJwtTokens.paymentServiceToken(), body), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mySubscriptions(String token) {
        return restTemplate.exchange("/travelers/me/subscriptions", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), List.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> subscribersOf(UUID destinationId, UUID managerId) {
        return restTemplate.exchange("/destinations/" + destinationId + "/subscriptions", HttpMethod.GET,
                new HttpEntity<>(bearer(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId))),
                List.class).getBody();
    }

    private UUID createDestination(UUID managerId, BigDecimal price, int capacity) {
        return createDestination(managerId, price, capacity, LocalDate.now().plusDays(30));
    }

    private UUID createDestination(UUID managerId, BigDecimal price, int capacity, LocalDate startDate) {
        Map<String, Object> body = Map.of(
                "name", "Testville", "country", "Testland",
                "startDate", startDate.toString(), "endDate", startDate.plusDays(5).toString(),
                "managerId", managerId.toString(), "price", price, "capacity", capacity);
        ResponseEntity<Map> response = restTemplate.exchange("/destinations", HttpMethod.POST,
                jsonEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId), body), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private static List<Received> receivedFor(String subscriptionId) {
        return RECEIVED.stream()
                .filter(r -> subscriptionId.equals(r.body().path("subscriptionRef").asText()))
                .toList();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpEntity<Map<String, Object>> jsonEntity(String token, Map<String, Object> body) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
