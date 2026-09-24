package com.travelplan.travel;

import com.travelplan.travel.support.TestJwtTokens;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@code GAVE_FEEDBACK} relation
 * (docs/lets-travel-architecture-decisions.md §5): giving feedback on a
 * participated destination, its visibility rules, and the manager statistics
 * and ranking built from it.
 *
 * Same structure as {@link SubscriptionIntegrationTest}: one Testcontainers
 * Neo4j instance per class, real HTTP calls through {@code TestRestTemplate}.
 * One container is shared by all tests, so every test works on its own freshly
 * generated manager/traveler/destination ids and never asserts on global
 * counts.
 *
 * Participation needs an {@code ACTIVE} subscription on a destination that has
 * already ended, which the public API cannot create (subscribing to a started
 * destination is a 409). Those subscriptions are therefore seeded straight into
 * the graph with {@link Neo4jClient} — which also lets the tests seed
 * statuses the API cannot produce yet ({@code PENDING_PAYMENT}) to prove they
 * do not count as participation.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class FeedbackIntegrationTest {

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

    // ------------------------------------------------------------------ giving feedback

    @Test
    void participatedTravelerCanGiveFeedback() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID travelerId = participant(destinationId, "ACTIVE");

        ResponseEntity<Map> response = postFeedback(destinationId, travelerId, feedbackBody(5, "Wonderful trip"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .containsEntry("destinationId", destinationId.toString())
                .containsEntry("travelerId", travelerId.toString())
                .containsEntry("rating", 5)
                .containsEntry("comment", "Wonderful trip");
        assertThat(response.getBody().get("createdAt")).isNotNull();
    }

    @Test
    void commentIsOptional() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID travelerId = participant(destinationId, "ACTIVE");

        ResponseEntity<Map> response = postFeedback(destinationId, travelerId, Map.of("rating", 3));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("rating", 3);
        assertThat(response.getBody().get("comment")).isNull();
    }

    @Test
    void commentIsStoredAsPlainTextNeverInterpreted() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID travelerId = participant(destinationId, "ACTIVE");
        String hostile = "<script>alert('xss')</script> <img src=x onerror=alert(1)>";

        ResponseEntity<Map> response = postFeedback(destinationId, travelerId, feedbackBody(4, hostile));

        // Stored and returned byte for byte: not stripped, not HTML-encoded. Escaping is the
        // renderer's job (Angular escapes interpolation by default) — see ADR §5 addendum.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("comment", hostile);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/json");
    }

    @Test
    void feedbackAuthorIsAlwaysTheCallerNeverTheBody() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID travelerId = participant(destinationId, "ACTIVE");
        UUID someoneElse = UUID.randomUUID();
        Map<String, Object> body = feedbackBody(4, "spoof attempt");
        body.put("travelerId", someoneElse.toString());

        ResponseEntity<Map> response = postFeedback(destinationId, travelerId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("travelerId", travelerId.toString());
    }

    @Test
    void managerOrAdminWhoParticipatedMayAlsoGiveFeedback() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID adminId = participant(destinationId, "ACTIVE");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/feedback", HttpMethod.POST,
                new HttpEntity<>(feedbackBody(2, null), jsonHeaders(TestJwtTokens.tokenWithRoleAndSubject("ADMIN", adminId))),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void travelerWhoNeverSubscribedIsForbidden() {
        UUID destinationId = createPastDestination(UUID.randomUUID());

        ResponseEntity<Map> response = postFeedback(destinationId, UUID.randomUUID(), feedbackBody(5, "never went"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void cancelledSubscriptionIsNotParticipation() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID travelerId = participant(destinationId, "CANCELLED");

        ResponseEntity<Map> response = postFeedback(destinationId, travelerId, feedbackBody(1, "cancelled"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void pendingPaymentSubscriptionIsNotParticipation() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID travelerId = participant(destinationId, "PENDING_PAYMENT");

        ResponseEntity<Map> response = postFeedback(destinationId, travelerId, feedbackBody(5, "unpaid"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void feedbackOnADestinationThatHasNotEndedIsRejected() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createDestination(managerId, LocalDate.now().plusDays(10), LocalDate.now().plusDays(15));
        // Seeded ACTIVE directly: this destination is priced, so the real subscribe API would now
        // require a payment provider (Phase 4). An active subscription on a future trip is not enough.
        UUID travelerId = participant(destinationId, "ACTIVE");

        ResponseEntity<Map> response = postFeedback(destinationId, travelerId, feedbackBody(5, "too early"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void feedbackOnTheLastDayOfTheTripIsStillTooEarly() {
        // endDate must be strictly in the past: on its last day the trip is still going on.
        UUID destinationId = createDestination(
                UUID.randomUUID(), LocalDate.now().minusDays(3), LocalDate.now());
        UUID travelerId = participant(destinationId, "ACTIVE");

        ResponseEntity<Map> response = postFeedback(destinationId, travelerId, feedbackBody(5, "same day"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void feedbackIsAcceptedTheDayAfterTheTripEnds() {
        UUID destinationId = createDestination(
                UUID.randomUUID(), LocalDate.now().minusDays(4), LocalDate.now().minusDays(1));
        UUID travelerId = participant(destinationId, "ACTIVE");

        ResponseEntity<Map> response = postFeedback(destinationId, travelerId, feedbackBody(5, "just back"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void secondFeedbackFromTheSameTravelerIsAConflictAndKeepsTheFirst() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createPastDestination(managerId);
        UUID travelerId = participant(destinationId, "ACTIVE");
        assertThat(postFeedback(destinationId, travelerId, feedbackBody(5, "first")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second = postFeedback(destinationId, travelerId, feedbackBody(1, "second"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        List<Map<String, Object>> stored = listForDestination(destinationId, "TRAVEL_MANAGER", managerId);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0)).containsEntry("rating", 5).containsEntry("comment", "first");
    }

    @Test
    void feedbackOnAnUnknownDestinationIsNotFound() {
        ResponseEntity<Map> response = postFeedback(UUID.randomUUID(), UUID.randomUUID(), feedbackBody(5, "?"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void feedbackWithoutAValidTokenIsUnauthorized() {
        UUID destinationId = createPastDestination(UUID.randomUUID());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/feedback", HttpMethod.POST,
                new HttpEntity<>(feedbackBody(5, "anon"), jsonHeaders(null)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ratingOutsideOneToFiveIsRejected() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID travelerId = participant(destinationId, "ACTIVE");

        assertThat(postFeedback(destinationId, travelerId, Map.of("rating", 0)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postFeedback(destinationId, travelerId, Map.of("rating", 6)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postFeedback(destinationId, travelerId, Map.of("rating", -2)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // Nothing was recorded by the rejected attempts, so a valid one still succeeds.
        assertThat(postFeedback(destinationId, travelerId, Map.of("rating", 5)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void ratingBoundsOneAndFiveAreAccepted() {
        UUID destinationId = createPastDestination(UUID.randomUUID());

        assertThat(postFeedback(destinationId, participant(destinationId, "ACTIVE"), Map.of("rating", 1))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(postFeedback(destinationId, participant(destinationId, "ACTIVE"), Map.of("rating", 5))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void missingOrNonIntegerRatingIsRejected() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID travelerId = participant(destinationId, "ACTIVE");

        assertThat(postFeedback(destinationId, travelerId, Map.of("comment", "no rating")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // 4.5 must not be silently truncated to 4, nor "4" coerced.
        assertThat(postFeedback(destinationId, travelerId, Map.of("rating", 4.5)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postFeedback(destinationId, travelerId, Map.of("rating", "4")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankOrOversizedCommentIsRejected() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID travelerId = participant(destinationId, "ACTIVE");

        assertThat(postFeedback(destinationId, travelerId, feedbackBody(4, "")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postFeedback(destinationId, travelerId, feedbackBody(4, "  \n\t ")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postFeedback(destinationId, travelerId, feedbackBody(4, "x".repeat(1001))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // Exactly at the limit is fine.
        assertThat(postFeedback(destinationId, travelerId, feedbackBody(4, "x".repeat(1000))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    // ------------------------------------------------------------------ visibility

    @Test
    void owningManagerSeesFeedbackOfTheirOwnDestination() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createPastDestination(managerId);
        UUID travelerId = participant(destinationId, "ACTIVE");
        postFeedback(destinationId, travelerId, feedbackBody(4, "nice"));

        List<Map<String, Object>> rows = listForDestination(destinationId, "TRAVEL_MANAGER", managerId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("travelerId", travelerId.toString())
                .containsEntry("rating", 4)
                .containsEntry("comment", "nice")
                .containsEntry("destinationId", destinationId.toString())
                .containsEntry("destinationName", "Testville");
    }

    @Test
    void anotherManagerCannotSeeFeedbackOfSomeoneElsesDestination() {
        UUID destinationId = createPastDestination(UUID.randomUUID());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/feedback", HttpMethod.GET,
                authorized(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", UUID.randomUUID())), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminSeesFeedbackOfAnyDestinationAndTravelerCannot() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        postFeedback(destinationId, participant(destinationId, "ACTIVE"), feedbackBody(3, null));

        assertThat(listForDestination(destinationId, "ADMIN", UUID.randomUUID())).hasSize(1);

        ResponseEntity<Map> asTraveler = restTemplate.exchange(
                "/destinations/" + destinationId + "/feedback", HttpMethod.GET,
                authorized(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", UUID.randomUUID())), Map.class);
        assertThat(asTraveler.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listingFeedbackOfAnUnknownDestinationIsNotFound() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + UUID.randomUUID() + "/feedback", HttpMethod.GET,
                authorized(TestJwtTokens.tokenWithRole("ADMIN")), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void travelerSeesOnlyTheirOwnFeedback() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID mine = participant(destinationId, "ACTIVE");
        UUID theirs = participant(destinationId, "ACTIVE");
        postFeedback(destinationId, mine, feedbackBody(5, "mine"));
        postFeedback(destinationId, theirs, feedbackBody(1, "theirs"));

        ResponseEntity<List> response = restTemplate.exchange(
                "/travelers/me/feedback", HttpMethod.GET,
                authorized(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", mine)), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) response.getBody().get(0);
        assertThat(row).containsEntry("comment", "mine").containsEntry("travelerId", mine.toString());
    }

    @Test
    void onlyAdminsCanListAllFeedback() {
        UUID destinationId = createPastDestination(UUID.randomUUID());
        UUID travelerId = participant(destinationId, "ACTIVE");
        postFeedback(destinationId, travelerId, feedbackBody(4, "for the admin list"));

        // Newest first (ORDER BY createdAt DESC): the row this test just created always lands
        // on the default first page, however much other tests' data the shared database holds.
        ResponseEntity<Map> asAdmin = restTemplate.exchange(
                "/feedback", HttpMethod.GET, authorized(TestJwtTokens.tokenWithRole("ADMIN")), Map.class);
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) asAdmin.getBody().get("content")).anySatisfy(row ->
                assertThat(asMap(row)).containsEntry("travelerId", travelerId.toString()));

        for (String role : List.of("TRAVEL_MANAGER", "TRAVELER")) {
            ResponseEntity<Map> denied = restTemplate.exchange(
                    "/feedback", HttpMethod.GET, authorized(TestJwtTokens.tokenWithRole(role)), Map.class);
            assertThat(denied.getStatusCode()).as(role).isEqualTo(HttpStatus.FORBIDDEN);
        }
        ResponseEntity<Map> anonymous = restTemplate.exchange(
                "/feedback", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listAllFeedbackIsPaginatedWithDistinctNonOverlappingPages() {
        // Baseline first: the database is shared with other tests, so the exact total varies —
        // this only checks the *increment* our own 5 rows add, and that consecutive pages never
        // repeat a row.
        long before = totalFeedbackCount();
        UUID destinationId = createPastDestination(UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            postFeedback(destinationId, participant(destinationId, "ACTIVE"), feedbackBody(3, "page-test-" + i));
        }

        Map<String, Object> firstPage = feedbackPage(0, 2);
        assertThat(firstPage).containsEntry("page", 0).containsEntry("size", 2)
                .containsEntry("totalElements", (int) (before + 5));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstContent = (List<Map<String, Object>>) firstPage.get("content");
        assertThat(firstContent).hasSize(2);

        Map<String, Object> secondPage = feedbackPage(1, 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondContent = (List<Map<String, Object>>) secondPage.get("content");
        assertThat(secondContent).hasSize(2);

        List<Object> firstIds = firstContent.stream().map(r -> r.get("id")).toList();
        List<Object> secondIds = secondContent.stream().map(r -> r.get("id")).toList();
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
    }

    @Test
    void feedbackPageAndSizeAreClampedRatherThanRejected() {
        assertThat(feedbackPage(-1, 20)).containsEntry("page", 0);
        assertThat(feedbackPage(0, 0)).containsEntry("size", 1);
        assertThat(feedbackPage(0, 5000)).containsEntry("size", 100);
    }

    @Test
    void softDeletedDestinationHidesItsFeedbackEverywhere() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createPastDestination(managerId);
        UUID travelerId = participant(destinationId, "ACTIVE");
        postFeedback(destinationId, travelerId, feedbackBody(2, "will vanish"));
        // A second, surviving destination, so the manager stays in the ranking.
        UUID survivor = createPastDestination(managerId);
        postFeedback(survivor, participant(survivor, "ACTIVE"), feedbackBody(4, "stays"));

        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/destinations/" + destinationId, HttpMethod.DELETE,
                authorized(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId)), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Per-destination list: gone (404, like the destination itself).
        ResponseEntity<Map> perDestination = restTemplate.exchange(
                "/destinations/" + destinationId + "/feedback", HttpMethod.GET,
                authorized(TestJwtTokens.tokenWithRole("ADMIN")), Map.class);
        assertThat(perDestination.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Giving feedback on it: gone too.
        assertThat(postFeedback(destinationId, participant(destinationId, "ACTIVE"), feedbackBody(5, "late"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The traveler's own history no longer shows it.
        ResponseEntity<List> mine = restTemplate.exchange(
                "/travelers/me/feedback", HttpMethod.GET,
                authorized(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)), List.class);
        assertThat(mine.getBody()).isEmpty();
        // The admin's global list no longer shows it.
        ResponseEntity<Map> all = restTemplate.exchange(
                "/feedback", HttpMethod.GET, authorized(TestJwtTokens.tokenWithRole("ADMIN")), Map.class);
        assertThat((List<?>) all.getBody().get("content")).noneSatisfy(row ->
                assertThat(asMap(row)).containsEntry("comment", "will vanish"));
        // Stats count only the survivor: 1 travel, 1 feedback, average 4.0 (not 3.0).
        Map<String, Object> stats = stats(managerId);
        assertThat(stats).containsEntry("activeTravels", 1).containsEntry("feedbackCount", 1)
                .containsEntry("averageRating", 4.0);
    }

    // ------------------------------------------------------------------ manager statistics

    @Test
    void managerStatsReportNumbersAndPastRatings() {
        UUID managerId = UUID.randomUUID();
        UUID pastA = createPastDestination(managerId);
        UUID pastB = createPastDestination(managerId);
        UUID pastNoFeedback = createPastDestination(managerId);
        UUID future = createDestination(managerId, LocalDate.now().plusDays(20), LocalDate.now().plusDays(25));

        // pastA: ratings 5 and 4; pastB: rating 2 => 3 feedbacks, sum 11, average 3.67
        postFeedback(pastA, participant(pastA, "ACTIVE"), Map.of("rating", 5));
        postFeedback(pastA, participant(pastA, "ACTIVE"), Map.of("rating", 4));
        postFeedback(pastB, participant(pastB, "ACTIVE"), Map.of("rating", 2));

        // Subscribers: an ACTIVE traveler on two of the manager's travels counts once; a
        // PENDING_PAYMENT and a CANCELLED subscription do not count. The 3 feedback givers above
        // are ACTIVE subscribers on their destination too, so they count: 3 + 1 (shared) = 4.
        UUID shared = UUID.randomUUID();
        seedSubscription(shared, pastNoFeedback, "ACTIVE");
        seedSubscription(shared, future, "ACTIVE");
        seedSubscription(UUID.randomUUID(), future, "PENDING_PAYMENT");
        seedSubscription(UUID.randomUUID(), future, "CANCELLED");

        Map<String, Object> stats = stats(managerId);

        assertThat(stats).containsEntry("managerId", managerId.toString())
                .containsEntry("activeTravels", 4)
                .containsEntry("pastTravels", 3)
                .containsEntry("subscribers", 4)
                .containsEntry("feedbackCount", 3)
                .containsEntry("averageRating", 3.67);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pastRatings = (List<Map<String, Object>>) stats.get("pastRatings");
        assertThat(pastRatings).hasSize(3)
                .noneSatisfy(r -> assertThat(r).containsEntry("destinationId", future.toString()));
        assertThat(pastRatings).filteredOn(r -> pastA.toString().equals(r.get("destinationId")))
                .singleElement().satisfies(r -> assertThat(r)
                        .containsEntry("feedbackCount", 2).containsEntry("averageRating", 4.5));
        assertThat(pastRatings).filteredOn(r -> pastB.toString().equals(r.get("destinationId")))
                .singleElement().satisfies(r -> assertThat(r)
                        .containsEntry("feedbackCount", 1).containsEntry("averageRating", 2.0));
        assertThat(pastRatings).filteredOn(r -> pastNoFeedback.toString().equals(r.get("destinationId")))
                .singleElement().satisfies(r -> {
                    assertThat(r).containsEntry("feedbackCount", 0);
                    assertThat(r.get("averageRating")).isNull();
                });
    }

    @Test
    void managerStatsAreOpenToEveryKnownRoleButNotToAnonymous() {
        UUID managerId = UUID.randomUUID();
        createPastDestination(managerId);

        for (String role : List.of("ADMIN", "TRAVEL_MANAGER", "TRAVELER")) {
            ResponseEntity<Map> response = restTemplate.exchange(
                    "/managers/" + managerId + "/stats", HttpMethod.GET,
                    authorized(TestJwtTokens.tokenWithRole(role)), Map.class);
            assertThat(response.getStatusCode()).as(role).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<Map> anonymous = restTemplate.exchange(
                "/managers/" + managerId + "/stats", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> noRole = restTemplate.exchange(
                "/managers/" + managerId + "/stats", HttpMethod.GET,
                authorized(TestJwtTokens.validTokenWithoutRole()), Map.class);
        assertThat(noRole.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void statsOfAManagerWithNothingPublishedAreZeros() {
        Map<String, Object> stats = stats(UUID.randomUUID());

        assertThat(stats).containsEntry("activeTravels", 0).containsEntry("pastTravels", 0)
                .containsEntry("subscribers", 0).containsEntry("feedbackCount", 0);
        assertThat(stats.get("averageRating")).isNull();
        assertThat((List<?>) stats.get("pastRatings")).isEmpty();
    }

    // ------------------------------------------------------------------ ranking

    @Test
    void rankingOrdersManagersByAverageRatingThenFeedbackCount() {
        UUID best = UUID.randomUUID();       // 5.0 over 1 feedback
        UUID steady = UUID.randomUUID();     // 4.0 over 2 feedbacks
        UUID thin = UUID.randomUUID();       // 4.0 over 1 feedback
        UUID unrated = UUID.randomUUID();    // no feedback: last
        UUID bestDest = createPastDestination(best);
        postFeedback(bestDest, participant(bestDest, "ACTIVE"), Map.of("rating", 5));
        UUID steadyDest = createPastDestination(steady);
        postFeedback(steadyDest, participant(steadyDest, "ACTIVE"), Map.of("rating", 5));
        postFeedback(steadyDest, participant(steadyDest, "ACTIVE"), Map.of("rating", 3));
        UUID thinDest = createPastDestination(thin);
        postFeedback(thinDest, participant(thinDest, "ACTIVE"), Map.of("rating", 4));
        UUID unratedDest = createPastDestination(unrated);
        // Since the "Dashboards" ADR addendum the ranking is a performance score (damped rating +
        // income + traveler volume, see DashboardRankingIntegrationTest). Give the four managers the
        // same number of ACTIVE travelers (2) so this test keeps isolating the rating component;
        // payment-service is unreachable here (partial ranking), so income is out of the score.
        seedSubscription(UUID.randomUUID(), bestDest, "ACTIVE");
        seedSubscription(UUID.randomUUID(), thinDest, "ACTIVE");
        seedSubscription(UUID.randomUUID(), unratedDest, "ACTIVE");
        seedSubscription(UUID.randomUUID(), unratedDest, "ACTIVE");

        // size=1000: the database is shared with other tests (no @BeforeEach cleanup in this
        // class), so the default page (20) could miss our managers behind others' higher-scored
        // ones — requesting everything keeps this test's relative-order assertion meaningful.
        ResponseEntity<Map> response = restTemplate.exchange(
                "/managers/ranking?size=1000", HttpMethod.GET, authorized(TestJwtTokens.tokenWithRole("ADMIN")), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) response.getBody().get("content");
        // The database is shared with other tests: check the relative order of our four managers.
        List<String> ours = ranking.stream()
                .map(r -> (String) r.get("managerId"))
                .filter(id -> List.of(best, steady, thin, unrated).stream().anyMatch(m -> m.toString().equals(id)))
                .toList();
        assertThat(ours).containsExactly(best.toString(), steady.toString(), thin.toString(), unrated.toString());

        Map<String, Object> steadyRow = ranking.stream()
                .filter(r -> steady.toString().equals(r.get("managerId"))).findFirst().orElseThrow();
        assertThat(steadyRow).containsEntry("averageRating", 4.0).containsEntry("feedbackCount", 2)
                .containsEntry("activeTravels", 1);
        Map<String, Object> unratedRow = ranking.stream()
                .filter(r -> unrated.toString().equals(r.get("managerId"))).findFirst().orElseThrow();
        assertThat(unratedRow.get("averageRating")).isNull();
        // rank is the 1-based position in the list.
        for (int i = 0; i < ranking.size(); i++) {
            assertThat(ranking.get(i)).containsEntry("rank", i + 1);
        }
    }

    @Test
    void rankingIsAdminOnly() {
        for (String role : List.of("TRAVEL_MANAGER", "TRAVELER")) {
            ResponseEntity<Map> denied = restTemplate.exchange(
                    "/managers/ranking", HttpMethod.GET, authorized(TestJwtTokens.tokenWithRole(role)), Map.class);
            assertThat(denied.getStatusCode()).as(role).isEqualTo(HttpStatus.FORBIDDEN);
        }
        ResponseEntity<Map> anonymous = restTemplate.exchange(
                "/managers/ranking", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rankingIsPaginatedWithRankPreservingAbsolutePositionAcrossPages() {
        UUID mine = UUID.randomUUID();
        UUID myDest = createPastDestination(mine);
        postFeedback(myDest, participant(myDest, "ACTIVE"), Map.of("rating", 5));

        // size=1000: locate this manager's ABSOLUTE rank in the whole (shared-database) ranking
        // first — other tests' managers may score higher, so this manager is not assumed to be #1.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> whole = (List<Map<String, Object>>) rankingPage(0, 1000).get("content");
        Map<String, Object> mineInWhole = whole.stream()
                .filter(r -> mine.toString().equals(r.get("managerId"))).findFirst().orElseThrow();
        int myRank = (int) mineInWhole.get("rank");

        // A one-item page landing exactly on that rank must show the SAME manager at the SAME
        // rank — paging slices the one full ranking, it does not re-rank each page in isolation.
        Map<String, Object> onlyMyPage = rankingPage(myRank - 1, 1);
        assertThat(onlyMyPage).containsEntry("page", myRank - 1).containsEntry("size", 1);
        assertThat(((Number) onlyMyPage.get("totalElements")).intValue()).isEqualTo(whole.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) onlyMyPage.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0)).containsEntry("rank", myRank).containsEntry("managerId", mine.toString());
    }

    @Test
    void rankingPageAndSizeAreClampedRatherThanRejected() {
        assertThat(rankingPage(-1, 20)).containsEntry("page", 0);
        assertThat(rankingPage(0, 0)).containsEntry("size", 1);
        assertThat(rankingPage(0, 5000)).containsEntry("size", 100);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rankingPage(int page, int size) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/managers/ranking?page=" + page + "&size=" + size, HttpMethod.GET,
                authorized(TestJwtTokens.tokenWithRole("ADMIN")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    void managerWithOnlySoftDeletedDestinationsLeavesTheRanking() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createPastDestination(managerId);
        postFeedback(destinationId, participant(destinationId, "ACTIVE"), Map.of("rating", 5));
        restTemplate.exchange("/destinations/" + destinationId, HttpMethod.DELETE,
                authorized(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId)), Void.class);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/managers/ranking?size=1000", HttpMethod.GET, authorized(TestJwtTokens.tokenWithRole("ADMIN")), Map.class);

        @SuppressWarnings("unchecked")
        List<Object> ranking = (List<Object>) response.getBody().get("content");
        assertThat(ranking).noneSatisfy(row ->
                assertThat(asMap(row)).containsEntry("managerId", managerId.toString()));
    }

    // ------------------------------------------------------------------ helpers

    private UUID createPastDestination(UUID managerId) {
        return createDestination(managerId, LocalDate.now().minusDays(20), LocalDate.now().minusDays(15));
    }

    private UUID createDestination(UUID managerId, LocalDate startDate, LocalDate endDate) {
        HttpHeaders headers = jsonHeaders(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId));
        Map<String, Object> body = Map.of(
                "name", "Testville", "country", "Testland",
                "startDate", startDate.toString(), "endDate", endDate.toString(),
                "managerId", managerId.toString(), "price", 100.00, "capacity", 10);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    /** A fresh traveler holding a subscription with the given status on {@code destinationId}. */
    private UUID participant(UUID destinationId, String status) {
        UUID travelerId = UUID.randomUUID();
        seedSubscription(travelerId, destinationId, status);
        return travelerId;
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

    private ResponseEntity<Map> postFeedback(UUID destinationId, UUID travelerId, Map<String, Object> body) {
        return restTemplate.exchange(
                "/destinations/" + destinationId + "/feedback", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId))),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listForDestination(UUID destinationId, String role, UUID callerId) {
        ResponseEntity<List> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/feedback", HttpMethod.GET,
                authorized(TestJwtTokens.tokenWithRoleAndSubject(role, callerId)), List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats(UUID managerId) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/managers/" + managerId + "/stats", HttpMethod.GET,
                authorized(TestJwtTokens.tokenWithRole("TRAVELER")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object row) {
        return (Map<String, Object>) row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> feedbackPage(int page, int size) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/feedback?page=" + page + "&size=" + size, HttpMethod.GET,
                authorized(TestJwtTokens.tokenWithRole("ADMIN")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private long totalFeedbackCount() {
        return ((Number) feedbackPage(0, 1).get("totalElements")).longValue();
    }

    private static Map<String, Object> feedbackBody(int rating, String comment) {
        Map<String, Object> body = new HashMap<>();
        body.put("rating", rating);
        if (comment != null) {
            body.put("comment", comment);
        }
        return body;
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpEntity<Void> authorized(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
