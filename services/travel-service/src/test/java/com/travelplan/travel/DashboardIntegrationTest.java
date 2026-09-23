package com.travelplan.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.travelplan.travel.service.JwtService;
import com.travelplan.travel.service.PaymentStatsClient;
import com.travelplan.travel.support.TestJwtTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
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
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests of the manager/admin dashboards, the upgraded manager
 * ranking and the traveler statistics (docs/lets-travel-architecture-decisions.md,
 * "Dashboards" addendum) on a seeded scenario: numbers per travel and per month,
 * counts, ranking order that follows income, ownership, admin override, and
 * graceful degradation when payment-service is unreachable.
 *
 * payment-service is stubbed with a plain JDK {@link HttpServer} (same technique
 * as {@link SubscriptionPaymentIntegrationTest}) serving {@code GET /payments/income}
 * and {@code GET /payments/summary}, so the real {@code PaymentStatsClient}
 * runs over a real socket and the credentials it presents can be asserted: the
 * {@code service:travel} token for the income call, the caller's own token for
 * the summary. The graph is wiped before every test so global figures
 * (admin dashboard, ranking) are exact. One Testcontainers Neo4j
 * (neo4j:5.26.6-community).
 *
 * Subscriptions and feedback are seeded straight into the graph: the public API
 * cannot subscribe to a travel that already started, nor rate one that has not ended.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class DashboardIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final YearMonth THIS_MONTH = YearMonth.now(ZoneOffset.UTC);
    private static final YearMonth LAST_MONTH = THIS_MONTH.minusMonths(1);
    private static final YearMonth TWO_MONTHS_AGO = THIS_MONTH.minusMonths(2);

    /** One request received by the stubbed payment-service. */
    private record Received(String path, String query, String authorization, String requestId) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    /** What GET /payments/income answers with. */
    private static final List<Map<String, Object>> INCOME_ROWS = new CopyOnWriteArrayList<>();
    /** What GET /payments/summary answers with. */
    private static volatile Map<String, Object> summaryBody = Map.of();
    /** {@code true} makes the stub answer 500 (payment-service is down). */
    private static volatile boolean stubDown = false;

    // Started eagerly (static initializer): it must be listening before Spring resolves the lazy
    // @DynamicPropertySource supplier that references its port.
    private static final HttpServer STUB_PAYMENT_SERVICE = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/payments", exchange -> {
                RECEIVED.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("X-Request-Id")));
                if (stubDown) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
                Object body = switch (exchange.getRequestURI().getPath()) {
                    case "/payments/income" -> Map.of("rows", new ArrayList<>(INCOME_ROWS));
                    case "/payments/summary" -> summaryBody;
                    default -> null;
                };
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] out = JSON.writeValueAsBytes(body);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
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
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void resetGraphAndStub() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
        RECEIVED.clear();
        INCOME_ROWS.clear();
        summaryBody = Map.of();
        stubDown = false;
    }

    // ============================================================ the seeded scenario
    //
    //   M1 (managerOne)  A  past, ends -15d   feedback 5, 4      travelers t1 t2 t3
    //                    B  past, ends -30d   feedback 2          traveler  t4
    //                    C  upcoming                              travelers t3 t5
    //                    income  A: last month 100 EUR + this month 50 EUR
    //                            B: this month 30 EUR + 20 USD
    //                            C: two months ago 10 EUR
    //   M2 (managerTwo)  D  past, ends -5d    feedback 5          traveler  t6
    //                    income  D: this month 200 EUR
    //   M3 (managerThree) E upcoming, nothing at all
    //
    // M1: 3 travels, 5 distinct travelers, average (5+4+2)/3 = 3.67, EUR 190 + USD 20.

    private record Scenario(UUID m1, UUID m2, UUID m3, UUID a, UUID b, UUID c, UUID d, UUID e) {
    }

    private Scenario seedScenario() {
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        UUID m3 = UUID.randomUUID();
        UUID a = createDestination(m1, "Alpha", -20, -15);
        UUID b = createDestination(m1, "Bravo", -35, -30);
        UUID c = createDestination(m1, "Charlie", 20, 25);
        UUID d = createDestination(m2, "Delta", -10, -5);
        UUID e = createDestination(m3, "Echo", 30, 35);

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        UUID t4 = UUID.randomUUID();
        UUID t5 = UUID.randomUUID();
        UUID t6 = UUID.randomUUID();
        seedSubscription(t1, a, "ACTIVE");
        seedSubscription(t2, a, "ACTIVE");
        seedSubscription(t3, a, "ACTIVE");
        seedSubscription(t4, b, "ACTIVE");
        seedSubscription(t3, c, "ACTIVE");
        seedSubscription(t5, c, "ACTIVE");
        seedSubscription(UUID.randomUUID(), c, "PENDING_PAYMENT");
        seedSubscription(UUID.randomUUID(), c, "CANCELLED");
        seedSubscription(t6, d, "ACTIVE");

        seedFeedback(t1, a, 5, 3);
        seedFeedback(t2, a, 4, 2);
        seedFeedback(t4, b, 2, 1);
        seedFeedback(t6, d, 5, 0);

        income(a, LAST_MONTH, "EUR", 100.00);
        income(a, THIS_MONTH, "EUR", 50.00);
        income(b, THIS_MONTH, "EUR", 30.00);
        income(b, THIS_MONTH, "USD", 20.00);
        income(c, TWO_MONTHS_AGO, "EUR", 10.00);
        income(d, THIS_MONTH, "EUR", 200.00);
        return new Scenario(m1, m2, m3, a, b, c, d, e);
    }

    // ============================================================ manager dashboard

    private record ManagerDashboardFixture(Scenario s, Map<String, Object> dashboard) {
    }

    /**
     * Seeds the shared scenario and fetches M1's dashboard once. Split out so the
     * former single mega-test below (41 assertions, java:S5961) can be broken into
     * several focused tests, each reseeding its own scenario like every other test
     * in this class, and each still asserting on a coherent slice of the response.
     */
    private ManagerDashboardFixture managerDashboardFixture() {
        Scenario s = seedScenario();
        Map<String, Object> dashboard = getOk("/managers/me/dashboard", manager(s.m1()));
        return new ManagerDashboardFixture(s, dashboard);
    }

    @Test
    void managerDashboardReportsTripsAndTravelerCounts() {
        ManagerDashboardFixture f = managerDashboardFixture();

        assertThat(f.dashboard()).containsEntry("managerId", f.s().m1().toString()).containsEntry("partial", false);
        assertThat(map(f.dashboard(), "trips")).containsEntry("organized", 3).containsEntry("past", 2)
                .containsEntry("ongoing", 0).containsEntry("upcoming", 1);
        // Distinct ACTIVE travelers: t1 t2 t3 t4 t5 (t3 is on two travels, the PENDING/CANCELLED do not count).
        assertThat(f.dashboard()).containsEntry("travelers", 5);
    }

    @Test
    void managerDashboardReportsRating() {
        ManagerDashboardFixture f = managerDashboardFixture();

        Map<String, Object> rating = map(f.dashboard(), "rating");
        assertThat(rating).containsEntry("feedbackCount", 3);
        assertThat(num(rating.get("average"))).isCloseTo(3.67, within(0.005));
    }

    @Test
    void managerDashboardReportsIncomeTotalsAndByMonthBreakdown() {
        ManagerDashboardFixture f = managerDashboardFixture();

        Map<String, Object> income = map(f.dashboard(), "income");
        assertThat(income).containsEntry("referenceCurrency", "EUR").containsEntry("months", 6);
        assertThat(totals(income, "totals")).containsEntry("EUR", 190.0).containsEntry("USD", 20.0);
        assertThat(num(income.get("amount"))).isEqualTo(190.0);
        // The window (6 months incl. this one) holds everything seeded.
        assertThat(totals(income, "windowTotals")).containsEntry("EUR", 190.0).containsEntry("USD", 20.0);
        List<Map<String, Object>> byMonth = list(income, "byMonth");
        assertThat(byMonth).extracting(m -> m.get("month")).containsExactly(
                THIS_MONTH.minusMonths(5).toString(), THIS_MONTH.minusMonths(4).toString(),
                THIS_MONTH.minusMonths(3).toString(), TWO_MONTHS_AGO.toString(), LAST_MONTH.toString(),
                THIS_MONTH.toString());
        assertThat(monthAmount(byMonth, TWO_MONTHS_AGO)).isEqualTo(10.0);
        assertThat(monthAmount(byMonth, LAST_MONTH)).isEqualTo(100.0);
        assertThat(monthAmount(byMonth, THIS_MONTH)).isEqualTo(80.0);          // 50 + 30 (EUR only)
        assertThat(totals(monthOf(byMonth, THIS_MONTH), "totals")).containsEntry("EUR", 80.0).containsEntry("USD", 20.0);
        assertThat(monthAmount(byMonth, THIS_MONTH.minusMonths(5))).isZero();   // empty months are kept
    }

    @Test
    void managerDashboardReportsPerTravelRows() {
        ManagerDashboardFixture f = managerDashboardFixture();

        List<Map<String, Object>> travels = list(f.dashboard(), "travels");
        assertThat(travels).hasSize(3);
        Map<String, Object> a = travelRow(travels, f.s().a());
        assertThat(a).containsEntry("status", "PAST").containsEntry("subscribers", 3).containsEntry("feedbackCount", 2)
                .containsEntry("capacity", 10);
        assertThat(num(a.get("averageRating"))).isEqualTo(4.5);
        assertThat(totals(a, "income")).containsEntry("EUR", 150.0);
        assertThat(num(a.get("incomeAmount"))).isEqualTo(150.0);
        Map<String, Object> b = travelRow(travels, f.s().b());
        assertThat(b).containsEntry("subscribers", 1);
        assertThat(totals(b, "income")).containsEntry("EUR", 30.0).containsEntry("USD", 20.0);
        Map<String, Object> c = travelRow(travels, f.s().c());
        assertThat(c).containsEntry("status", "UPCOMING").containsEntry("subscribers", 2).containsEntry("feedbackCount", 0);
        assertThat(c.get("averageRating")).isNull();
        assertThat(totals(c, "income")).containsEntry("EUR", 10.0);
    }

    @Test
    void managerDashboardReportsRecentFeedback() {
        ManagerDashboardFixture f = managerDashboardFixture();

        // Recent feedback: this manager's three, newest first, none of M2's.
        List<Map<String, Object>> recent = list(f.dashboard(), "recentFeedback");
        assertThat(recent).extracting(fb -> fb.get("rating")).containsExactly(2, 4, 5);
        assertThat(recent).extracting(fb -> fb.get("destinationId"))
                .containsExactly(f.s().b().toString(), f.s().a().toString(), f.s().a().toString());
    }

    @Test
    void theWindowFollowsMonthsAndIsClamped() {
        Scenario s = seedScenario();

        Map<String, Object> twoMonths = map(getOk("/managers/me/dashboard?months=2", manager(s.m1())), "income");
        assertThat(list(twoMonths, "byMonth")).hasSize(2);
        assertThat(totals(twoMonths, "windowTotals")).containsEntry("EUR", 180.0);   // last + this month, not the older 10
        assertThat(totals(twoMonths, "totals")).containsEntry("EUR", 190.0);         // lifetime unchanged

        assertThat(list(map(getOk("/managers/me/dashboard?months=999", manager(s.m1())), "income"), "byMonth"))
                .hasSize(24);
        assertThat(list(map(getOk("/managers/me/dashboard?months=0", manager(s.m1())), "income"), "byMonth"))
                .hasSize(1);
    }

    @Test
    void theIncomeCallCarriesTheTravelServiceTokenAndOnlyThat() {
        Scenario s = seedScenario();

        getOk("/managers/me/dashboard", manager(s.m1()));

        Received call = RECEIVED.stream().filter(r -> r.path().equals("/payments/income")).findFirst().orElseThrow();
        assertThat(call.authorization()).startsWith("Bearer ");
        JsonNode claims = claimsOf(call.authorization().substring("Bearer ".length()));
        assertThat(claims.get("sub").asText()).isEqualTo("service:travel");
        assertThat(claims.has("role")).as("a service token carries no role").isFalse();
        assertThat(call.requestId()).as("X-Request-Id is propagated").isNotBlank();
    }

    @Test
    void aManagerCannotReadAnotherManagersDashboard() {
        Scenario s = seedScenario();

        ResponseEntity<Map> other = get("/managers/me/dashboard?managerId=" + s.m1(), manager(s.m2()));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Their own, with or without the explicit id, is fine (and is theirs, not M1's).
        assertThat(get("/managers/me/dashboard?managerId=" + s.m2(), manager(s.m2())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> mine = getOk("/managers/me/dashboard", manager(s.m2()));
        assertThat(mine).containsEntry("managerId", s.m2().toString());
        assertThat(map(mine, "trips")).containsEntry("organized", 1);
        assertThat(totals(map(mine, "income"), "totals")).containsEntry("EUR", 200.0);
    }

    @Test
    void anAdminMayReadAnyManagersDashboardWithManagerId() {
        Scenario s = seedScenario();

        Map<String, Object> dashboard = getOk("/managers/me/dashboard?managerId=" + s.m1(),
                TestJwtTokens.tokenWithRole("ADMIN"));

        assertThat(dashboard).containsEntry("managerId", s.m1().toString());
        assertThat(map(dashboard, "trips")).containsEntry("organized", 3);
        assertThat(totals(map(dashboard, "income"), "totals")).containsEntry("EUR", 190.0);
    }

    @Test
    void aTravelerAndAnonymousCallersAreRefusedTheManagerDashboard() {
        assertThat(get("/managers/me/dashboard", TestJwtTokens.tokenWithRole("TRAVELER")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/managers/me/dashboard", TestJwtTokens.validTokenWithoutRole()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/managers/me/dashboard", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aManagerWithNothingPublishedGetsZerosNotAnError() {
        Map<String, Object> dashboard = getOk("/managers/me/dashboard", manager(UUID.randomUUID()));

        assertThat(map(dashboard, "trips")).containsEntry("organized", 0);
        assertThat(dashboard).containsEntry("travelers", 0);
        assertThat(map(dashboard, "rating").get("average")).isNull();
        assertThat(list(dashboard, "travels")).isEmpty();
        assertThat(list(dashboard, "recentFeedback")).isEmpty();
        assertThat(num(map(dashboard, "income").get("amount"))).isZero();
    }

    @Test
    void managerDashboardDegradesWhenPaymentServiceIsDown() {
        Scenario s = seedScenario();
        stubDown = true;

        Map<String, Object> dashboard = getOk("/managers/me/dashboard", manager(s.m1()));

        assertThat(dashboard).containsEntry("partial", true);
        assertThat(dashboard.get("income")).isNull();
        // Everything the graph owns is still there.
        assertThat(map(dashboard, "trips")).containsEntry("organized", 3).containsEntry("past", 2);
        assertThat(dashboard).containsEntry("travelers", 5);
        assertThat(map(dashboard, "rating")).containsEntry("feedbackCount", 3);
        List<Map<String, Object>> travels = list(dashboard, "travels");
        assertThat(travels).hasSize(3).allSatisfy(t -> {
            assertThat(t.get("income")).isNull();
            assertThat(t.get("incomeAmount")).isNull();
        });
        assertThat(list(dashboard, "recentFeedback")).hasSize(3);
    }

    @Test
    void anUnreachablePaymentServiceYieldsNoFiguresInsteadOfAnException() {
        // Nothing listens on port 1: the connection itself is refused (not a 5xx answer).
        PaymentStatsClient unreachable = new PaymentStatsClient(jwtService, "http://localhost:1");

        assertThat(unreachable.fetchIncome()).isEmpty();
        assertThat(unreachable.fetchSummary("Bearer whatever", UUID.randomUUID())).isEmpty();
    }

    // ============================================================ admin dashboard

    private record AdminDashboardFixture(Scenario s, Map<String, Object> dashboard) {
    }

    /**
     * Seeds the shared scenario and fetches the admin dashboard once. Split out so
     * the former single mega-test below (37 assertions, java:S5961) can be broken
     * into several focused tests, each reseeding its own scenario like every other
     * test in this class, and each still asserting on a coherent slice of the response.
     */
    private AdminDashboardFixture adminDashboardFixture() {
        Scenario s = seedScenario();
        Map<String, Object> dashboard = getOk("/admin/dashboard", TestJwtTokens.tokenWithRole("ADMIN"));
        return new AdminDashboardFixture(s, dashboard);
    }

    @Test
    void adminDashboardReportsPartialFlagAndTotals() {
        AdminDashboardFixture f = adminDashboardFixture();

        assertThat(f.dashboard()).containsEntry("partial", false).containsEntry("referenceCurrency", "EUR")
                .containsEntry("months", 6);

        // Number of organised travels + satisfaction.
        Map<String, Object> totals = map(f.dashboard(), "totals");
        assertThat(totals).containsEntry("managers", 3).containsEntry("organizedTravels", 5)
                .containsEntry("pastTravels", 3).containsEntry("ongoingTravels", 0)
                .containsEntry("upcomingTravels", 2).containsEntry("activeTravelers", 6)
                .containsEntry("feedbackCount", 4);
        assertThat(num(totals.get("averageRating"))).isEqualTo(4.0);            // (5+4+2+5)/4
    }

    @Test
    void adminDashboardReportsIncomeByMonth() {
        AdminDashboardFixture f = adminDashboardFixture();

        // Income of the last months, platform-wide.
        Map<String, Object> income = map(f.dashboard(), "income");
        assertThat(totals(income, "totals")).containsEntry("EUR", 390.0).containsEntry("USD", 20.0);
        List<Map<String, Object>> byMonth = list(income, "byMonth");
        assertThat(byMonth).hasSize(6);
        assertThat(monthAmount(byMonth, THIS_MONTH)).isEqualTo(280.0);          // 50 + 30 + 200
        assertThat(monthAmount(byMonth, LAST_MONTH)).isEqualTo(100.0);
        assertThat(monthAmount(byMonth, TWO_MONTHS_AGO)).isEqualTo(10.0);
    }

    @Test
    void adminDashboardReportsTopManagers() {
        AdminDashboardFixture f = adminDashboardFixture();
        Scenario s = f.s();

        // Top managers: score, income, rating.
        List<Map<String, Object>> byScore = list(f.dashboard(), "topManagersByScore");
        assertThat(byScore).extracting(m -> m.get("managerId"))
                .containsExactly(s.m1().toString(), s.m2().toString(), s.m3().toString());
        assertThat(byScore).extracting(m -> m.get("rank")).containsExactly(1, 2, 3);
        assertThat(num(byScore.get(0).get("score"))).isCloseTo(76.63, within(0.011));
        assertThat(num(byScore.get(1).get("score"))).isCloseTo(63.17, within(0.011));
        assertThat(num(byScore.get(2).get("score"))).isCloseTo(25.0, within(0.011));

        List<Map<String, Object>> byIncome = list(f.dashboard(), "topManagersByIncome");
        assertThat(byIncome).extracting(m -> m.get("managerId"))
                .containsExactly(s.m2().toString(), s.m1().toString());         // M3 earned nothing: absent
        assertThat(byIncome).extracting(m -> m.get("rank")).containsExactly(1, 2);
        assertThat(num(byIncome.get(0).get("incomeAmount"))).isEqualTo(200.0);

        List<Map<String, Object>> byRating = list(f.dashboard(), "topManagersByRating");
        assertThat(byRating).extracting(m -> m.get("managerId"))
                .containsExactly(s.m2().toString(), s.m1().toString());         // M2 damped 3.33 > M1 3.25; M3 unrated: absent
    }

    @Test
    void adminDashboardReportsTopTravels() {
        AdminDashboardFixture f = adminDashboardFixture();
        Scenario s = f.s();

        assertThat(list(f.dashboard(), "topTravelsByIncome")).extracting(t -> t.get("destinationId"))
                .containsExactly(s.d().toString(), s.a().toString(), s.b().toString(), s.c().toString());
        assertThat(list(f.dashboard(), "topTravelsByRating")).extracting(t -> t.get("destinationId"))
                .containsExactly(s.a().toString(), s.d().toString(), s.b().toString());
    }

    @Test
    void adminDashboardReportsTravelHistory() {
        AdminDashboardFixture f = adminDashboardFixture();
        Scenario s = f.s();

        // Detailed history: past travels only, latest end first, with subscribers, income, rating.
        List<Map<String, Object>> history = list(f.dashboard(), "travelHistory");
        assertThat(history).extracting(t -> t.get("destinationId"))
                .containsExactly(s.d().toString(), s.a().toString(), s.b().toString());
        Map<String, Object> historyA = travelRow(history, s.a());
        assertThat(historyA).containsEntry("subscribers", 3).containsEntry("feedbackCount", 2)
                .containsEntry("managerId", s.m1().toString());
        assertThat(num(historyA.get("averageRating"))).isEqualTo(4.5);
        assertThat(num(historyA.get("incomeAmount"))).isEqualTo(150.0);
    }

    @Test
    void adminDashboardReportsRecentFeedback() {
        AdminDashboardFixture f = adminDashboardFixture();

        // Feedbacks to assess satisfaction: all four, newest first.
        assertThat(list(f.dashboard(), "recentFeedback")).extracting(fb -> fb.get("rating"))
                .containsExactly(5, 2, 4, 5);
    }

    @Test
    void adminDashboardIsAdminOnly() {
        assertThat(get("/admin/dashboard", TestJwtTokens.tokenWithRole("TRAVEL_MANAGER")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/admin/dashboard", TestJwtTokens.tokenWithRole("TRAVELER")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/admin/dashboard", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminDashboardDegradesWhenPaymentServiceIsDown() {
        Scenario s = seedScenario();
        stubDown = true;

        Map<String, Object> dashboard = getOk("/admin/dashboard", TestJwtTokens.tokenWithRole("ADMIN"));

        assertThat(dashboard).containsEntry("partial", true);
        assertThat(dashboard.get("income")).isNull();
        assertThat(list(dashboard, "topManagersByIncome")).isEmpty();
        assertThat(list(dashboard, "topTravelsByIncome")).isEmpty();
        // The rest is served: counts, ratings, history, feedbacks, and a ranking scored without income.
        assertThat(map(dashboard, "totals")).containsEntry("organizedTravels", 5).containsEntry("managers", 3);
        assertThat(list(dashboard, "topManagersByRating")).hasSize(2);
        assertThat(list(dashboard, "travelHistory")).hasSize(3)
                .allSatisfy(t -> assertThat(t.get("income")).isNull());
        assertThat(list(dashboard, "recentFeedback")).hasSize(4);
        List<Map<String, Object>> byScore = list(dashboard, "topManagersByScore");
        assertThat(byScore).hasSize(3).allSatisfy(m -> {
            assertThat(m).containsEntry("partial", true);
            assertThat(m.get("income")).isNull();
        });
        assertThat(byScore.get(0)).containsEntry("managerId", s.m1().toString());
    }

    // ============================================================ ranking

    @Test
    void rankingKeepsItsOriginalFieldsAndAddsScoreIncomeAndSubscribers() {
        Scenario s = seedScenario();

        List<Map<String, Object>> ranking = getList("/managers/ranking", TestJwtTokens.tokenWithRole("ADMIN"));

        assertThat(ranking).extracting(m -> m.get("managerId"))
                .containsExactly(s.m1().toString(), s.m2().toString(), s.m3().toString());
        Map<String, Object> first = ranking.get(0);
        // Original fields, same meaning.
        assertThat(first).containsEntry("rank", 1).containsEntry("feedbackCount", 3).containsEntry("activeTravels", 3);
        assertThat(num(first.get("averageRating"))).isCloseTo(3.67, within(0.005));
        // Added fields.
        assertThat(first).containsEntry("subscribers", 5).containsEntry("partial", false);
        assertThat(num(first.get("dampedRating"))).isCloseTo(3.25, within(0.005));       // (11 + 15) / (3 + 5) = 3.25
        assertThat(totals(first, "income")).containsEntry("EUR", 190.0).containsEntry("USD", 20.0);
        assertThat(num(first.get("incomeAmount"))).isEqualTo(190.0);
        assertThat(num(first.get("score"))).isCloseTo(76.63, within(0.011));
        assertThat(ranking.get(2).get("averageRating")).isNull();                        // M3: unrated
    }

    @Test
    void rankingOrderFollowsIncome() {
        Scenario s = seedScenario();
        assertThat(rankingOrder()).startsWith(s.m1().toString(), s.m2().toString());

        // M2's travel suddenly brings in far more: income now outweighs M1's rating/volume lead.
        INCOME_ROWS.removeIf(r -> s.d().toString().equals(r.get("travelId")));
        income(s.d(), THIS_MONTH, "EUR", 5000.00);

        assertThat(rankingOrder()).startsWith(s.m2().toString(), s.m1().toString());

        // ...and when M1's income overtakes again, the order flips back.
        income(s.a(), THIS_MONTH, "EUR", 20000.00);

        assertThat(rankingOrder()).startsWith(s.m1().toString(), s.m2().toString());
    }

    @Test
    void oneFiveStarReviewDoesNotBeatManyExcellentOnes() {
        UUID lucky = UUID.randomUUID();
        UUID established = UUID.randomUUID();
        UUID luckyDest = createDestination(lucky, "One review", -20, -15);
        UUID establishedDest = createDestination(established, "Many reviews", -20, -15);
        seedFeedback(UUID.randomUUID(), luckyDest, 5, 1);
        // 20 reviews: nineteen 5-star, one 4-star => 4.95 raw average, below the lucky manager's 5.0.
        for (int i = 0; i < 20; i++) {
            seedFeedback(UUID.randomUUID(), establishedDest, i == 0 ? 4 : 5, i + 1);
        }

        List<Map<String, Object>> ranking = getList("/managers/ranking", TestJwtTokens.tokenWithRole("ADMIN"));

        assertThat(ranking).extracting(m -> m.get("managerId"))
                .containsExactly(established.toString(), lucky.toString());
        assertThat(num(ranking.get(0).get("averageRating"))).isLessThan(num(ranking.get(1).get("averageRating")));
    }

    @Test
    void rankingDegradesWithoutIncomeWhenPaymentServiceIsDown() {
        Scenario s = seedScenario();
        stubDown = true;

        List<Map<String, Object>> ranking = getList("/managers/ranking", TestJwtTokens.tokenWithRole("ADMIN"));

        assertThat(ranking).hasSize(3).allSatisfy(m -> {
            assertThat(m).containsEntry("partial", true);
            assertThat(m.get("income")).isNull();
            assertThat(m.get("incomeAmount")).isNull();
        });
        assertThat(ranking.get(0)).containsEntry("managerId", s.m1().toString());
    }

    // ============================================================ traveler statistics

    private record TravelerScenario(UUID traveler, String token) {
    }

    /**
     * ACTIVE on two past travels (rated one of them) and one upcoming; CANCELLED on two; PENDING_PAYMENT on one;
     * ACTIVE on a past travel that was soft-deleted (must vanish).
     */
    private TravelerScenario seedTraveler() {
        UUID manager = UUID.randomUUID();
        UUID traveler = UUID.randomUUID();
        UUID past1 = createDestination(manager, "Past one", -20, -15);
        UUID past2 = createDestination(manager, "Past two", -40, -35);
        UUID upcoming = createDestination(manager, "Upcoming", 20, 25);
        UUID cancelledFuture = createDestination(manager, "Cancelled future", 20, 25);
        UUID cancelledPast = createDestination(manager, "Cancelled past", -60, -55);
        UUID pending = createDestination(manager, "Pending", 30, 35);
        UUID deleted = createDestination(manager, "Deleted", -70, -65);
        seedSubscription(traveler, past1, "ACTIVE");
        seedSubscription(traveler, past2, "ACTIVE");
        seedSubscription(traveler, upcoming, "ACTIVE");
        seedSubscription(traveler, cancelledFuture, "CANCELLED");
        seedSubscription(traveler, cancelledPast, "CANCELLED");
        seedSubscription(traveler, pending, "PENDING_PAYMENT");
        seedSubscription(traveler, deleted, "ACTIVE");
        seedFeedback(traveler, past1, 4, 1);
        restTemplate.exchange("/destinations/" + deleted, HttpMethod.DELETE, authorized(manager(manager)), Void.class);

        summaryBody = Map.of("userId", traveler.toString(), "totalCount", 3, "mostUsedProvider", "STRIPE",
                "byProvider", List.of(
                        Map.of("provider", "STRIPE", "count", 2, "totals", Map.of("EUR", 140.0)),
                        Map.of("provider", "PAYPAL", "count", 1, "totals", Map.of("USD", 25.0))));
        return new TravelerScenario(traveler, TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", traveler));
    }

    @Test
    void travelerStatsCountParticipationsCancellationsFeedbackAndPreferredProvider() {
        TravelerScenario t = seedTraveler();

        Map<String, Object> stats = getOk("/travelers/me/stats", t.token());

        assertThat(stats).containsEntry("travelerId", t.traveler().toString()).containsEntry("partial", false)
                .containsEntry("pastParticipationCount", 2).containsEntry("upcomingSubscriptionCount", 1)
                .containsEntry("cancellationCount", 2).containsEntry("feedbackGivenCount", 1)
                .containsEntry("preferredPaymentProvider", "STRIPE");
        Map<String, Object> payments = map(stats, "payments");
        assertThat(payments).containsEntry("totalCount", 3);
        assertThat(list(payments, "byProvider")).extracting(p -> p.get("provider")).containsExactly("STRIPE", "PAYPAL");

        List<Map<String, Object>> past = list(stats, "pastParticipations");
        assertThat(past).extracting(p -> p.get("name")).containsExactly("Past one", "Past two");   // latest first
        assertThat(past.get(0)).containsEntry("feedbackGiven", true).containsKeys("startDate", "endDate");
        assertThat(past.get(1)).containsEntry("feedbackGiven", false);
    }

    @Test
    void travelerStatsForwardTheCallersOwnTokenToPaymentService() {
        TravelerScenario t = seedTraveler();

        getOk("/travelers/me/stats", t.token());

        Received call = RECEIVED.stream().filter(r -> r.path().equals("/payments/summary")).findFirst().orElseThrow();
        assertThat(call.authorization()).isEqualTo("Bearer " + t.token());
        assertThat(call.query()).isEqualTo("userId=" + t.traveler());
        assertThat(call.requestId()).isNotBlank();
    }

    @Test
    void aTravelerCannotReadSomeoneElsesStatsButAnAdminCan() {
        TravelerScenario t = seedTraveler();
        String stranger = TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", UUID.randomUUID());
        String manager = TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", UUID.randomUUID());

        assertThat(get("/travelers/me/stats?travelerId=" + t.traveler(), stranger).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/travelers/me/stats?travelerId=" + t.traveler(), manager).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Asking for one's own id explicitly is fine.
        assertThat(get("/travelers/me/stats?travelerId=" + t.traveler(), t.token()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String admin = TestJwtTokens.tokenWithRole("ADMIN");
        Map<String, Object> asAdmin = getOk("/travelers/me/stats?travelerId=" + t.traveler(), admin);
        assertThat(asAdmin).containsEntry("travelerId", t.traveler().toString())
                .containsEntry("pastParticipationCount", 2);
        // The admin's own token is what payment-service sees, with the target's id.
        Received call = RECEIVED.stream().filter(r -> ("Bearer " + admin).equals(r.authorization())).findFirst()
                .orElseThrow();
        assertThat(call.query()).isEqualTo("userId=" + t.traveler());
    }

    @Test
    void travelerStatsNeedAKnownRole() {
        assertThat(get("/travelers/me/stats", TestJwtTokens.validTokenWithoutRole()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/travelers/me/stats", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The payment-service token is not a user.
        assertThat(get("/travelers/me/stats", TestJwtTokens.paymentServiceToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void travelerStatsDegradeWhenPaymentServiceIsDown() {
        TravelerScenario t = seedTraveler();
        stubDown = true;

        Map<String, Object> stats = getOk("/travelers/me/stats", t.token());

        assertThat(stats).containsEntry("partial", true);
        assertThat(stats.get("preferredPaymentProvider")).isNull();
        assertThat(stats.get("payments")).isNull();
        assertThat(stats).containsEntry("pastParticipationCount", 2).containsEntry("cancellationCount", 2)
                .containsEntry("feedbackGivenCount", 1).containsEntry("upcomingSubscriptionCount", 1);
        assertThat(list(stats, "pastParticipations")).hasSize(2);
    }

    @Test
    void aTravelerWithNoHistoryHasZeroStatsAndNoPreferredProvider() {
        summaryBody = Map.of("totalCount", 0, "byProvider", List.of());

        Map<String, Object> stats = getOk("/travelers/me/stats",
                TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", UUID.randomUUID()));

        assertThat(stats).containsEntry("partial", false).containsEntry("pastParticipationCount", 0)
                .containsEntry("cancellationCount", 0).containsEntry("feedbackGivenCount", 0);
        assertThat(stats.get("preferredPaymentProvider")).isNull();
        assertThat(list(stats, "pastParticipations")).isEmpty();
    }

    // ============================================================ helpers: seeding

    private static void income(UUID travelId, YearMonth month, String currency, double total) {
        Map<String, Object> row = new HashMap<>();
        row.put("travelId", travelId.toString());
        row.put("month", month.toString());
        row.put("currency", currency);
        row.put("total", total);
        row.put("count", 1);
        INCOME_ROWS.add(row);
    }

    private UUID createDestination(UUID managerId, String name, int startOffsetDays, int endOffsetDays) {
        LocalDate today = LocalDate.now();
        Map<String, Object> body = Map.of(
                "name", name, "country", "Testland",
                "startDate", today.plusDays(startOffsetDays).toString(),
                "endDate", today.plusDays(endOffsetDays).toString(),
                "managerId", managerId.toString(), "price", 100.00, "capacity", 10);
        ResponseEntity<Map> response = restTemplate.exchange("/destinations", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(manager(managerId))), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private void seedSubscription(UUID travelerId, UUID destinationId, String status) {
        neo4jClient.query("""
                        MATCH (d:Destination {id: $destinationId})
                        MERGE (t:TravelerRef {userId: $travelerId})
                        CREATE (t)-[:SUBSCRIBED {id: randomUUID(), status: $status, subscribedAt: datetime(),
                                                  cancelledAt: null}]->(d)
                        """)
                .bindAll(Map.of("destinationId", destinationId.toString(),
                        "travelerId", travelerId.toString(), "status", status))
                .run();
    }

    private void seedFeedback(UUID travelerId, UUID destinationId, int rating, int ageDays) {
        neo4jClient.query("""
                        MATCH (d:Destination {id: $destinationId})
                        MERGE (t:TravelerRef {userId: $travelerId})
                        CREATE (t)-[:GAVE_FEEDBACK {id: randomUUID(), rating: $rating, comment: 'seeded',
                                                     createdAt: datetime() - duration({days: $ageDays})}]->(d)
                        """)
                .bindAll(Map.of("destinationId", destinationId.toString(), "travelerId", travelerId.toString(),
                        "rating", rating, "ageDays", ageDays))
                .run();
    }

    // ============================================================ helpers: HTTP and JSON

    private static String manager(UUID managerId) {
        return TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId);
    }

    private List<String> rankingOrder() {
        return getList("/managers/ranking", TestJwtTokens.tokenWithRole("ADMIN")).stream()
                .map(m -> (String) m.get("managerId")).toList();
    }

    private ResponseEntity<Map> get(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.GET,
                token == null ? new HttpEntity<>(new HttpHeaders()) : authorized(token), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOk(String path, String token) {
        ResponseEntity<Map> response = get(path, token);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String path, String token) {
        ResponseEntity<List> response = restTemplate.exchange(path, HttpMethod.GET, authorized(token), List.class);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        assertThat(parent.get(key)).as(key).isNotNull();
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> parent, String key) {
        assertThat(parent.get(key)).as(key).isNotNull();
        return (List<Map<String, Object>>) parent.get(key);
    }

    /** A {@code currency -> amount} map with the amounts as doubles, for {@code containsEntry("EUR", 190.0)}. */
    private static Map<String, Object> totals(Map<String, Object> parent, String key) {
        Map<String, Object> converted = new HashMap<>();
        map(parent, key).forEach((currency, amount) -> converted.put(currency, num(amount)));
        return converted;
    }

    private static double num(Object value) {
        return ((Number) value).doubleValue();
    }

    private static Map<String, Object> travelRow(List<Map<String, Object>> rows, UUID destinationId) {
        return rows.stream().filter(r -> destinationId.toString().equals(r.get("destinationId")))
                .findFirst().orElseThrow(() -> new AssertionError("no row for " + destinationId));
    }

    private static Map<String, Object> monthOf(List<Map<String, Object>> byMonth, YearMonth month) {
        return byMonth.stream().filter(m -> month.toString().equals(m.get("month")))
                .findFirst().orElseThrow(() -> new AssertionError("no month " + month));
    }

    private static double monthAmount(List<Map<String, Object>> byMonth, YearMonth month) {
        return num(monthOf(byMonth, month).get("amount"));
    }

    private static JsonNode claimsOf(String jwt) {
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        } catch (IOException ex) {
            throw new AssertionError("not a JWT: " + jwt, ex);
        }
    }

    private static HttpEntity<Void> authorized(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
