package com.travelplan.travel;

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

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code GET /travelers/me/recommendations}
 * (docs/lets-travel-architecture-decisions.md §7), against a real Neo4j.
 *
 * Same structure as {@link FeedbackIntegrationTest}: one Testcontainers Neo4j per
 * class, real HTTP through {@code TestRestTemplate}, subscriptions seeded straight
 * into the graph (the API cannot subscribe to a past trip), feedback given through
 * the real endpoint. Unlike the other classes, the graph is <b>wiped before every
 * test</b>: recommendations look at the whole catalogue, so exact-list assertions
 * are only meaningful on a graph this test owns (the container is private to this
 * class, so nothing else is affected).
 *
 * The catalogue of {@link #seedCatalogue()} is the worked example of the ADR §7:
 * the same numbers are asserted here (through the graph) and in
 * {@code RecommendationScorerTest} (through the formula alone).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class RecommendationIntegrationTest {

    @Container
    static final Neo4jContainer<?> neo4j =
            new Neo4jContainer<>("neo4j:5.26.6-community")
                    .withAdminPassword("test_password_only");

    @DynamicPropertySource
    static void registerNeo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("NEO4J_HOST", neo4j::getHost);
        registry.add("NEO4J_PORT", () -> String.valueOf(neo4j.getMappedPort(7687)));
        registry.add("NEO4J_USERNAME", () -> "neo4j");
        registry.add("NEO4J_PASSWORD", neo4j::getAdminPassword);
        registry.add("JWT_SIGNING_KEY", () -> TestJwtTokens.SIGNING_KEY);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Neo4jClient neo4jClient;

    private final UUID managerId = UUID.randomUUID();

    @BeforeEach
    void wipeGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    // ------------------------------------------------------------------ two travelers, two lists

    @Test
    void twoTravelersWithDifferentHistoriesGetDifferentCorrectlyOrderedLists() {
        Catalogue cat = seedCatalogue();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        // Alice loved Lisbon (5) and hated the Alps (1); Bob loved the Alps (5).
        participateAndRate(alice, cat.lisbon, 5);
        participateAndRate(alice, cat.alps, 1);
        participateAndRate(bob, cat.alps, 5);

        List<Map<String, Object>> forAlice = recommendations(alice, "TRAVELER", "");
        List<Map<String, Object>> forBob = recommendations(bob, "TRAVELER", "");

        // Alice: Algarve 3x(3 country+1 surf+1 hostel+1 price)=18 ; Porto 3x3 (country) -3x1 (hotel, Alps)=6 ;
        // Tokyo 0 ; Zermatt -3x(3+1+1+1)=-18.
        assertThat(names(forAlice)).containsExactly("Algarve Surf Week", "Porto Wine Trip", "Tokyo Food Tour", "Zermatt Ski Weekend");
        assertThat(scores(forAlice)).containsExactly(18.0, 6.0, 0.0, -18.0);
        // Bob: Zermatt 3x6=18 ; Porto 3x1 (hotel) = 3 ; Algarve and Tokyo 0 (Algarve leaves first).
        assertThat(names(forBob)).containsExactly("Zermatt Ski Weekend", "Porto Wine Trip", "Algarve Surf Week", "Tokyo Food Tour");
        assertThat(scores(forBob)).containsExactly(18.0, 3.0, 0.0, 0.0);
        // The whole point of the audit scenario: the two accounts do not get the same answer.
        assertThat(names(forAlice)).isNotEqualTo(names(forBob));
        assertThat(names(forAlice).get(0)).isNotEqualTo(names(forBob).get(0));
    }

    @Test
    void everyRecommendationExplainsItselfAndTheReasonsAddUpToTheScore() {
        Catalogue cat = seedCatalogue();
        UUID alice = UUID.randomUUID();
        participateAndRate(alice, cat.lisbon, 5);
        participateAndRate(alice, cat.alps, 1);

        List<Map<String, Object>> forAlice = recommendations(alice, "TRAVELER", "");

        assertThat(forAlice).allSatisfy(r -> assertThat(reasons(r)).isNotEmpty());
        // The score is the sum of the signed points quoted at the end of the reasons (nothing was truncated here).
        // Compiled once outside the loop below (java:S9142): the pattern never changes between iterations.
        Pattern signedPointsPattern = Pattern.compile("\\(([+-][0-9.]+)\\)( - pulls this destination down)?$");
        for (Map<String, Object> r : forAlice) {
            double sum = reasons(r).stream()
                    .filter(text -> !text.startsWith("Nothing in common"))
                    .mapToDouble(text -> {
                        Matcher m = signedPointsPattern.matcher(text);
                        assertThat(m.find()).as(text).isTrue();
                        return Double.parseDouble(m.group(1));
                    }).sum();
            assertThat(sum).as((String) r.get("name")).isEqualTo(((Number) r.get("score")).doubleValue());
        }
        Map<String, Object> algarve = byName(forAlice, "Algarve Surf Week");
        assertThat(algarve).containsEntry("destinationId", cat.algarve.toString())
                .containsEntry("country", "PORTUGAL");
        assertThat(algarve.get("startDate")).isNotNull();
        assertThat(algarve.get("price")).isNotNull();
        // Case-insensitive matching: 'Surf'/'SUP' vs 'surf', 'Hostel' vs 'hostel', 'PORTUGAL' vs 'Portugal'.
        assertThat(reasons(algarve)).containsExactly(
                "same country (PORTUGAL) as \"Lisbon Surf Camp\", which you rated 5 (+9)",
                "shares activity 'Surf' with \"Lisbon Surf Camp\", which you rated 5 (+3)",
                "same accommodation type 'Hostel' as \"Lisbon Surf Camp\", which you rated 5 (+3)",
                "price 480.00 within 25% of 500.00 for \"Lisbon Surf Camp\", which you rated 5 (+3)");
        // Porto mixes a pull (Lisbon, 5) and a push (Alps, 1): both are explained.
        assertThat(reasons(byName(forAlice, "Porto Wine Trip"))).containsExactly(
                "same country (Portugal) as \"Lisbon Surf Camp\", which you rated 5 (+9)",
                "same accommodation type 'hotel' as \"Alps Ski Week\", which you rated 1 (-3) - pulls this destination down");
        // Nothing in common with the history: says so, does not invent a reason.
        assertThat(reasons(byName(forAlice, "Tokyo Food Tour"))).singleElement().asString()
                .startsWith("Nothing in common with your past trips or feedback");
    }

    // ------------------------------------------------------------------ what the history does to the ranking

    @Test
    void aFiveStarBoostsSimilarDestinationsAndAOneStarDemotesThem() {
        Catalogue cat = seedCatalogue();
        UUID fan = UUID.randomUUID();
        UUID critic = UUID.randomUUID();
        UUID lurker = UUID.randomUUID();
        participateAndRate(fan, cat.lisbon, 5);
        participateAndRate(critic, cat.lisbon, 1);
        seedSubscription(lurker, cat.lisbon, "ACTIVE");   // took part, never gave feedback

        List<Map<String, Object>> forFan = recommendations(fan, "TRAVELER", "");
        List<Map<String, Object>> forCritic = recommendations(critic, "TRAVELER", "");
        List<Map<String, Object>> forLurker = recommendations(lurker, "TRAVELER", "");

        // Same past trip, three opinions: the Portuguese trips are pulled up, pushed down, or nudged up.
        assertThat(names(forFan)).containsExactly(
                "Algarve Surf Week", "Porto Wine Trip", "Zermatt Ski Weekend", "Tokyo Food Tour");
        assertThat(scores(forFan)).containsExactly(18.0, 9.0, 0.0, 0.0);

        assertThat(names(forCritic)).containsExactly(
                "Zermatt Ski Weekend", "Tokyo Food Tour", "Porto Wine Trip", "Algarve Surf Week");
        assertThat(scores(forCritic)).containsExactly(0.0, 0.0, -9.0, -18.0);
        assertThat(reasons(byName(forCritic, "Algarve Surf Week"))).allSatisfy(r ->
                assertThat(r).contains("which you rated 1").endsWith("- pulls this destination down"));

        assertThat(names(forLurker)).containsExactly(
                "Algarve Surf Week", "Porto Wine Trip", "Zermatt Ski Weekend", "Tokyo Food Tour");
        assertThat(scores(forLurker)).containsExactly(6.0, 3.0, 0.0, 0.0);
        assertThat(reasons(byName(forLurker, "Porto Wine Trip"))).containsExactly(
                "same country (Portugal) as \"Lisbon Surf Camp\", which you took part in (+3)");
    }

    @Test
    void aTwoStarPushesDownAndAThreeStarIsNearlyNeutral() {
        Catalogue cat = seedCatalogue();
        UUID unimpressed = UUID.randomUUID();
        UUID shrug = UUID.randomUUID();
        participateAndRate(unimpressed, cat.lisbon, 2);
        participateAndRate(shrug, cat.lisbon, 3);

        assertThat(scoreOf(recommendations(unimpressed, "TRAVELER", ""), "Porto Wine Trip")).isEqualTo(-4.5);
        assertThat(scoreOf(recommendations(shrug, "TRAVELER", ""), "Porto Wine Trip")).isEqualTo(1.5);
    }

    @Test
    void aSoftDeletedHistoryDestinationNoLongerCounts() {
        Catalogue cat = seedCatalogue();
        UUID traveler = UUID.randomUUID();
        participateAndRate(traveler, cat.lisbon, 5);
        assertThat(scoreOf(recommendations(traveler, "TRAVELER", ""), "Porto Wine Trip")).isEqualTo(9.0);

        delete(cat.lisbon);

        // The traveler's only history is gone: back to the cold-start fallback.
        List<Map<String, Object>> after = recommendations(traveler, "TRAVELER", "");
        assertThat(after).allSatisfy(r -> assertThat(reasons(r)).singleElement().asString().startsWith("No history yet"));
        assertThat(scoreOf(after, "Porto Wine Trip")).isZero();
    }

    @Test
    void anActiveSubscriptionOnAFutureTripCountsAsParticipationAndItsOwnTripIsNotSuggested() {
        Catalogue cat = seedCatalogue();
        UUID traveler = UUID.randomUUID();
        seedSubscription(traveler, cat.algarve, "ACTIVE");

        List<Map<String, Object>> list = recommendations(traveler, "TRAVELER", "");

        assertThat(names(list)).doesNotContain("Algarve Surf Week");
        // Algarve (PT, surf, hostel, 480) is in the history: Porto shares the country (3), weight 1 => 3.
        assertThat(scoreOf(list, "Porto Wine Trip")).isEqualTo(3.0);
    }

    @Test
    void pendingPaymentAndCancelledSubscriptionsAreNotHistory() {
        Catalogue cat = seedCatalogue();
        UUID traveler = UUID.randomUUID();
        seedSubscription(traveler, cat.lisbon, "PENDING_PAYMENT");
        seedSubscription(traveler, cat.alps, "CANCELLED");

        List<Map<String, Object>> list = recommendations(traveler, "TRAVELER", "");

        // isNotEmpty() first (java:S5841): allSatisfy vacuously passes on an empty list, which would let this
        // test claim "pending/cancelled subscriptions are not history" without ever inspecting a real reason.
        assertThat(list).isNotEmpty()
                .allSatisfy(r -> assertThat(reasons(r)).singleElement().asString().startsWith("No history yet"));
    }

    // ------------------------------------------------------------------ who is eligible

    @Test
    void ineligibleDestinationsAreExcludedAndEligibleOnesKept() {
        UUID traveler = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        destination("Control", "Ctl", 100, today.plusDays(20), today.plusDays(25), 10, List.of(), List.of());
        UUID alreadyActive = destination("AlreadyActive", "Ctl", 100, today.plusDays(20), today.plusDays(25), 10, List.of(), List.of());
        UUID pendingLive = destination("PendingLive", "Ctl", 100, today.plusDays(20), today.plusDays(25), 10, List.of(), List.of());
        UUID cancelled = destination("Cancelled", "Ctl", 100, today.plusDays(20), today.plusDays(25), 10, List.of(), List.of());
        UUID pendingExpired = destination("PendingExpired", "Ctl", 100, today.plusDays(20), today.plusDays(25), 10, List.of(), List.of());
        destination("Started", "Ctl", 100, today.minusDays(1), today.plusDays(5), 10, List.of(), List.of());
        destination("StartsToday", "Ctl", 100, today, today.plusDays(5), 10, List.of(), List.of());
        UUID full = destination("Full", "Ctl", 100, today.plusDays(20), today.plusDays(25), 1, List.of(), List.of());
        UUID fullByPending = destination("FullByPending", "Ctl", 100, today.plusDays(20), today.plusDays(25), 1, List.of(), List.of());
        UUID notFull = destination("NotFullExpiredHold", "Ctl", 100, today.plusDays(20), today.plusDays(25), 1, List.of(), List.of());
        UUID deleted = destination("Deleted", "Ctl", 100, today.plusDays(20), today.plusDays(25), 10, List.of(), List.of());
        destination("Unlimited", "Ctl", 100, today.plusDays(20), today.plusDays(25), 10, List.of(), List.of());
        neo4jClient.query("MATCH (d:Destination {name: 'Unlimited'}) REMOVE d.capacity").run();

        seedSubscription(traveler, alreadyActive, "ACTIVE");
        seedPending(traveler, pendingLive, true);
        seedSubscription(traveler, cancelled, "CANCELLED");
        seedPending(traveler, pendingExpired, false);
        seedSubscription(other, full, "ACTIVE");
        seedPending(other, fullByPending, true);
        seedPending(other, notFull, false);
        delete(deleted);

        List<Map<String, Object>> list = recommendations(traveler, "TRAVELER", "");

        assertThat(names(list)).containsExactlyInAnyOrder(
                "Control", "Cancelled", "PendingExpired", "StartsToday", "NotFullExpiredHold", "Unlimited");
    }

    // ------------------------------------------------------------------ cold start

    @Test
    void coldStartRanksByActiveSubscribersThenSoonestStartAndSaysSo() {
        LocalDate today = LocalDate.now();
        UUID popular = destination("Popular", "A", 100, today.plusDays(50), today.plusDays(55), 10, List.of(), List.of());
        UUID mid = destination("Mid", "B", 100, today.plusDays(40), today.plusDays(45), 10, List.of(), List.of());
        destination("Quiet soon", "C", 100, today.plusDays(10), today.plusDays(15), 10, List.of(), List.of());
        destination("Quiet late", "D", 100, today.plusDays(30), today.plusDays(35), 10, List.of(), List.of());
        seedSubscription(UUID.randomUUID(), popular, "ACTIVE");
        seedSubscription(UUID.randomUUID(), popular, "ACTIVE");
        seedSubscription(UUID.randomUUID(), mid, "ACTIVE");
        // Neither a cancelled nor a pending subscription is popularity.
        seedSubscription(UUID.randomUUID(), mid, "CANCELLED");
        seedPending(UUID.randomUUID(), mid, true);

        List<Map<String, Object>> list = recommendations(UUID.randomUUID(), "TRAVELER", "");

        assertThat(names(list)).containsExactly("Popular", "Mid", "Quiet soon", "Quiet late");
        assertThat(scores(list)).containsExactly(2.0, 1.0, 0.0, 0.0);
        assertThat(reasons(list.get(0))).containsExactly(
                "No history yet (no participation, no feedback): ranked by popularity, then soonest start (2 active subscribers)");
        assertThat(reasons(list.get(1)).get(0)).endsWith("(1 active subscriber)");
    }

    // ------------------------------------------------------------------ limit

    @Test
    void limitDefaultsToTenIsClampedToFiftyAndAtLeastOne() {
        neo4jClient.query("""
                        UNWIND range(1, 55) AS i
                        CREATE (:Destination {id: randomUUID(), name: 'Bulk ' + i, country: 'Bulkland',
                                              startDate: date() + duration({days: 10}),
                                              endDate: date() + duration({days: 15}),
                                              managerId: $managerId, price: '100', capacity: 10, createdAt: datetime()})
                        """)
                .bindAll(Map.of("managerId", managerId.toString())).run();
        UUID traveler = UUID.randomUUID();

        assertThat(recommendations(traveler, "TRAVELER", "")).hasSize(10);
        assertThat(recommendations(traveler, "TRAVELER", "?limit=3")).hasSize(3);
        assertThat(recommendations(traveler, "TRAVELER", "?limit=1000")).hasSize(50);
        assertThat(recommendations(traveler, "TRAVELER", "?limit=0")).hasSize(1);
        assertThat(recommendations(traveler, "TRAVELER", "?limit=-5")).hasSize(1);
    }

    @Test
    void emptyCatalogueGivesAnEmptyList() {
        assertThat(recommendations(UUID.randomUUID(), "TRAVELER", "")).isEmpty();
    }

    // ------------------------------------------------------------------ caller only, admin override

    @Test
    void aTravelerSeesOnlyTheirOwnListAndCannotAskForSomeoneElses() {
        Catalogue cat = seedCatalogue();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        participateAndRate(alice, cat.lisbon, 5);
        participateAndRate(bob, cat.alps, 5);

        // Their own id in the query string is harmless...
        assertThat(get(alice, "TRAVELER", "?travelerId=" + alice).getStatusCode()).isEqualTo(HttpStatus.OK);
        // ...someone else's is refused, whatever the non-admin role.
        assertThat(get(alice, "TRAVELER", "?travelerId=" + bob).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(alice, "TRAVEL_MANAGER", "?travelerId=" + bob).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // A manager asking for themselves is a traveler like any other.
        assertThat(get(alice, "TRAVEL_MANAGER", "").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anAdminMayReadAnotherTravelersListButGetsTheirOwnByDefault() {
        Catalogue cat = seedCatalogue();
        UUID bob = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        participateAndRate(bob, cat.alps, 5);

        List<Map<String, Object>> bobsOwn = recommendations(bob, "TRAVELER", "");
        List<Map<String, Object>> adminAboutBob = recommendations(admin, "ADMIN", "?travelerId=" + bob);
        List<Map<String, Object>> adminOwn = recommendations(admin, "ADMIN", "");

        assertThat(names(adminAboutBob)).isEqualTo(names(bobsOwn));
        assertThat(scores(adminAboutBob)).isEqualTo(scores(bobsOwn));
        // The admin has no history of their own: cold start, not Bob's list.
        assertThat(reasons(adminOwn.get(0)).get(0)).startsWith("No history yet");
    }

    // ------------------------------------------------------------------ authentication / bad input

    @Test
    void withoutAValidTokenIsUnauthorized() {
        ResponseEntity<Map> anonymous = restTemplate.exchange(
                "/travelers/me/recommendations", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders garbage = new HttpHeaders();
        garbage.setBearerAuth("not-a-jwt");
        ResponseEntity<Map> bad = restTemplate.exchange(
                "/travelers/me/recommendations", HttpMethod.GET, new HttpEntity<>(garbage), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTokenWithoutAKnownRoleIsForbidden() {
        for (String token : List.of(TestJwtTokens.validTokenWithoutRole(), TestJwtTokens.paymentServiceToken(),
                TestJwtTokens.tokenWithRole("HACKER"))) {
            ResponseEntity<Map> response = restTemplate.exchange(
                    "/travelers/me/recommendations", HttpMethod.GET, authorized(token), Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void malformedParametersAreABadRequest() {
        UUID traveler = UUID.randomUUID();
        assertThat(get(traveler, "TRAVELER", "?limit=abc").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(traveler, "ADMIN", "?travelerId=not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ fixtures

    private record Catalogue(UUID lisbon, UUID alps, UUID algarve, UUID zermatt, UUID porto, UUID tokyo) {
    }

    /**
     * Two past trips the travelers' histories are built from, four future trips to be ranked.
     * Dates are chosen so that ties (Algarve/Tokyo with score 0) always resolve the same way.
     */
    private Catalogue seedCatalogue() {
        LocalDate today = LocalDate.now();
        UUID lisbon = destination("Lisbon Surf Camp", "Portugal", 500, today.minusDays(40), today.minusDays(35), 10,
                List.of("surf", "yoga"), List.of("hostel"));
        UUID alps = destination("Alps Ski Week", "Switzerland", 1200, today.minusDays(30), today.minusDays(25), 10,
                List.of("ski"), List.of("hotel"));
        UUID algarve = destination("Algarve Surf Week", "PORTUGAL", 480, today.plusDays(20), today.plusDays(25), 10,
                List.of("Surf", "SUP"), List.of("Hostel"));
        UUID zermatt = destination("Zermatt Ski Weekend", "Switzerland", 1100, today.plusDays(21), today.plusDays(24), 10,
                List.of("ski"), List.of("hotel"));
        UUID porto = destination("Porto Wine Trip", "Portugal", 700, today.plusDays(22), today.plusDays(26), 10,
                List.of("wine"), List.of("hotel"));
        UUID tokyo = destination("Tokyo Food Tour", "Japan", 2000, today.plusDays(23), today.plusDays(30), 10,
                List.of("cooking"), List.of("ryokan"));
        return new Catalogue(lisbon, alps, algarve, zermatt, porto, tokyo);
    }

    private UUID destination(String name, String country, int price, LocalDate start, LocalDate end, int capacity,
                             List<String> activities, List<String> accommodationTypes) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("country", country);
        body.put("startDate", start.toString());
        body.put("endDate", end.toString());
        body.put("managerId", managerId.toString());
        body.put("price", price + 0.00);
        body.put("capacity", capacity);
        body.put("activities", activities);
        body.put("accommodations", accommodationTypes.stream()
                .map(type -> Map.of("name", type + " place", "type", type)).toList());
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId))),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private void delete(UUID destinationId) {
        ResponseEntity<Void> response = restTemplate.exchange(
                "/destinations/" + destinationId, HttpMethod.DELETE,
                authorized(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId)), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** ACTIVE subscription on a past trip, then the real feedback endpoint. */
    private void participateAndRate(UUID travelerId, UUID destinationId, int rating) {
        seedSubscription(travelerId, destinationId, "ACTIVE");
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/feedback", HttpMethod.POST,
                new HttpEntity<>(Map.of("rating", rating),
                        jsonHeaders(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId))),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void seedSubscription(UUID travelerId, UUID destinationId, String status) {
        neo4jClient.query("""
                        MATCH (d:Destination {id: $destinationId})
                        MERGE (t:TravelerRef {userId: $travelerId})
                        CREATE (t)-[:SUBSCRIBED {status: $status, subscribedAt: datetime(), cancelledAt: null}]->(d)
                        """)
                .bindAll(Map.of("destinationId", destinationId.toString(),
                        "travelerId", travelerId.toString(), "status", status))
                .run();
    }

    private void seedPending(UUID travelerId, UUID destinationId, boolean holdStillValid) {
        neo4jClient.query("""
                        MATCH (d:Destination {id: $destinationId})
                        MERGE (t:TravelerRef {userId: $travelerId})
                        CREATE (t)-[:SUBSCRIBED {status: 'PENDING_PAYMENT', subscribedAt: datetime(),
                                                 expiresAt: datetime() + duration({hours: $hours}), cancelledAt: null}]->(d)
                        """)
                .bindAll(Map.of("destinationId", destinationId.toString(),
                        "travelerId", travelerId.toString(), "hours", holdStillValid ? 1 : -1))
                .run();
    }

    // ------------------------------------------------------------------ HTTP and result helpers

    private ResponseEntity<Object> get(UUID callerId, String role, String query) {
        return restTemplate.exchange(
                "/travelers/me/recommendations" + query, HttpMethod.GET,
                authorized(TestJwtTokens.tokenWithRoleAndSubject(role, callerId)), Object.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> recommendations(UUID callerId, String role, String query) {
        ResponseEntity<Object> response = get(callerId, role, query);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody();
    }

    private static List<String> names(List<Map<String, Object>> list) {
        return list.stream().map(r -> (String) r.get("name")).toList();
    }

    private static List<Double> scores(List<Map<String, Object>> list) {
        return list.stream().map(r -> ((Number) r.get("score")).doubleValue()).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> reasons(Map<String, Object> recommendation) {
        return (List<String>) recommendation.get("reasons");
    }

    private static Map<String, Object> byName(List<Map<String, Object>> list, String name) {
        return list.stream().filter(r -> name.equals(r.get("name"))).findFirst().orElseThrow();
    }

    private static double scoreOf(List<Map<String, Object>> list, String name) {
        return ((Number) byName(list, name).get("score")).doubleValue();
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpEntity<Void> authorized(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
