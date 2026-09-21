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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code GET /payments/income}, the aggregate that feeds
 * the manager/admin dashboards of travel-service (docs/lets-travel-architecture-decisions.md,
 * "Dashboards" addendum): only {@code COMPLETED}, travel-linked, active payments
 * count; grouped by travel, calendar month of <b>completion</b> (UTC) and
 * currency; readable by an {@code ADMIN} or the {@code service:travel} token only.
 *
 * Payments are created and completed through the public API (as travel-service's
 * data would be); the completion instant is then moved to a chosen month with a
 * direct SQL update, since a test cannot wait for months to pass. The
 * subscription-confirmation callback to travel-service (fired on completion) is
 * best-effort and simply fails against the unreachable default URL.
 * postgres:17.5-bookworm via Testcontainers.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentIncomeIntegrationTest {

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

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private static final YearMonth THIS_MONTH = YearMonth.now(ZoneOffset.UTC);
    private static final YearMonth LAST_MONTH = THIS_MONTH.minusMonths(1);

    @Test
    void groupsCompletedIncomeByTravelMonthAndCurrencyAndIgnoresEverythingElse() {
        UUID travelA = UUID.randomUUID();
        UUID travelB = UUID.randomUUID();

        completedPayment(travelA, 100.00, "EUR", LAST_MONTH);
        completedPayment(travelA, 50.50, "EUR", LAST_MONTH);
        completedPayment(travelA, 30.00, "EUR", THIS_MONTH);
        completedPayment(travelA, 20.00, "USD", THIS_MONTH);
        completedPayment(travelB, 75.00, "EUR", THIS_MONTH);
        // None of these is income: pending, failed, soft-deleted, not linked to a travel.
        createPayment(travelA, 999.00, "EUR");
        UUID failed = createPayment(travelA, 888.00, "EUR");
        patchStatus(failed, "FAILED");
        UUID deleted = completedPayment(travelA, 777.00, "EUR", THIS_MONTH);
        restTemplate.exchange("/payments/" + deleted, HttpMethod.DELETE, authorized(admin()), Void.class);
        UUID unlinked = createPayment(null, 666.00, "EUR");
        patchStatus(unlinked, "COMPLETED");

        List<Map<String, Object>> rows = income(admin());

        assertThat(rowsOf(rows, travelA)).containsExactlyInAnyOrder(
                row(travelA, LAST_MONTH, "EUR", 150.50, 2),
                row(travelA, THIS_MONTH, "EUR", 30.00, 1),
                row(travelA, THIS_MONTH, "USD", 20.00, 1));
        assertThat(rowsOf(rows, travelB)).containsExactly(row(travelB, THIS_MONTH, "EUR", 75.00, 1));
        assertThat(rows).noneSatisfy(r -> assertThat(r.get("travelId")).isNull());
    }

    @Test
    void theMonthIsTheMonthOfCompletionNotOfCreation() {
        UUID travel = UUID.randomUUID();
        UUID id = createPayment(travel, 40.00, "EUR");
        // Created two months ago (e.g. a MANUAL payment awaiting an admin) ...
        jdbc.update("UPDATE payments SET created_at = ? WHERE id = ?",
                THIS_MONTH.minusMonths(2).atDay(10).atStartOfDay().atOffset(ZoneOffset.UTC), id);
        // ... and only confirmed now: the money is taken now.
        patchStatus(id, "COMPLETED");

        assertThat(rowsOf(income(admin()), travel))
                .containsExactly(row(travel, THIS_MONTH, "EUR", 40.00, 1));
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM payments WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    void aPaymentThatIsNotCompletedHasNoCompletionInstant() {
        UUID pending = createPayment(UUID.randomUUID(), 12.00, "EUR");
        UUID failed = createPayment(UUID.randomUUID(), 12.00, "EUR");
        patchStatus(failed, "FAILED");

        assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM payments WHERE id = ?",
                Boolean.class, pending)).isTrue();
        assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM payments WHERE id = ?",
                Boolean.class, failed)).isTrue();
    }

    @Test
    void anAdminAndTheTravelServiceTokenMayReadIt() {
        assertThat(get("/payments/income", admin()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/payments/income", TestJwtTokens.travelServiceToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void everyoneElseIsRefused() {
        assertThat(get("/payments/income", TestJwtTokens.tokenFor(UUID.randomUUID(), "TRAVEL_MANAGER"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/payments/income", TestJwtTokens.tokenFor(UUID.randomUUID(), "TRAVELER"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // The identity-service token is scoped to DELETE /payments/by-user/{id}, not to this.
        assertThat(get("/payments/income", TestJwtTokens.serviceToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/payments/income", TestJwtTokens.validTokenWithoutRole()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/payments/income", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theTravelServiceTokenIsRefusedEverywhereElse() {
        // Scoped to its single endpoint: the regular payment routes treat it as an invalid credential.
        assertThat(get("/payments", TestJwtTokens.travelServiceToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/payments/summary", TestJwtTokens.travelServiceToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------ helpers

    private static String admin() {
        return TestJwtTokens.validToken();
    }

    /** Creates, completes and re-dates (completion instant, mid-month) a travel-linked payment. */
    private UUID completedPayment(UUID travelId, double amount, String currency, YearMonth completedIn) {
        UUID id = createPayment(travelId, amount, currency);
        patchStatus(id, "COMPLETED");
        jdbc.update("UPDATE payments SET completed_at = ? WHERE id = ?",
                completedIn.atDay(15).atTime(12, 0).atOffset(ZoneOffset.UTC), id);
        return id;
    }

    private UUID createPayment(UUID travelId, double amount, String currency) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", UUID.randomUUID().toString());
        body.put("amount", amount);
        body.put("currency", currency);
        if (travelId != null) {
            body.put("travelId", travelId.toString());
            body.put("subscriptionRef", UUID.randomUUID().toString());
        }
        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments", HttpMethod.POST, json(admin(), body), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private void patchStatus(UUID id, String status) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/payments/" + id + "/status", HttpMethod.PATCH, json(admin(), Map.of("status", status)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> income(String token) {
        ResponseEntity<Map> response = get("/payments/income", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("rows");
    }

    private List<Map<String, Object>> rowsOf(List<Map<String, Object>> rows, UUID travelId) {
        return rows.stream().filter(r -> travelId.toString().equals(r.get("travelId")))
                .map(PaymentIncomeIntegrationTest::normalized).toList();
    }

    /** Amounts arrive as JSON numbers; compare them as doubles. */
    private static Map<String, Object> normalized(Map<String, Object> row) {
        Map<String, Object> copy = new HashMap<>(row);
        copy.put("total", ((Number) row.get("total")).doubleValue());
        return copy;
    }

    private static Map<String, Object> row(UUID travelId, YearMonth month, String currency, double total, int count) {
        Map<String, Object> row = new HashMap<>();
        row.put("travelId", travelId.toString());
        row.put("month", month.toString());
        row.put("currency", currency);
        row.put("total", total);
        row.put("count", count);
        return row;
    }

    private ResponseEntity<Map> get(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.GET, token == null ? new HttpEntity<>(new HttpHeaders())
                : authorized(token), Map.class);
    }

    private static HttpEntity<Void> authorized(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private static HttpEntity<Map<String, Object>> json(String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
