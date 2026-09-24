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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code GET /travelers/me/itinerary-suggestions}
 * (docs/lets-travel-architecture-decisions.md §12): chains of eligible
 * destinations connected by active {@code TRANSPORT} edges, scored by the
 * same {@code RecommendationScorer} as a single-stop recommendation.
 *
 * Same structure as {@link RecommendationIntegrationTest}: itinerary
 * suggestions look at the whole catalogue (like a single recommendation), so
 * the graph is wiped before every test — exact-chain assertions are only
 * meaningful on a graph this test owns.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class ItinerarySuggestionIntegrationTest {

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

    @BeforeEach
    void wipeGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    void chainsTwoAndThreeStopsLongAreSuggestedInOrder() {
        UUID a = createDestination("Athens", "Greece");
        UUID b = createDestination("Rome", "Italy");
        UUID c = createDestination("Paris", "France");
        createTransport(a, b, 90);
        createTransport(b, c, 120);

        List<Map<String, Object>> suggestions = getSuggestions(UUID.randomUUID());

        assertThat(suggestions).isNotEmpty();
        List<List<String>> stopIdLists = suggestions.stream().map(this::stopIds).toList();
        assertThat(stopIdLists).contains(List.of(a.toString(), b.toString()));
        assertThat(stopIdLists).contains(List.of(a.toString(), b.toString(), c.toString()));

        Map<String, Object> threeStops = suggestions.stream()
                .filter(s -> stopIds(s).size() == 3)
                .findFirst().orElseThrow();
        assertThat(threeStops.get("totalDurationMinutes")).isEqualTo(210);
    }

    @Test
    void aDestinationUnreachableByTransportNeverAppearsInAChain() {
        UUID a = createDestination("Athens", "Greece");
        UUID b = createDestination("Rome", "Italy");
        createTransport(a, b, 90);
        UUID isolated = createDestination("Reykjavik", "Iceland");

        List<Map<String, Object>> suggestions = getSuggestions(UUID.randomUUID());

        assertThat(suggestions.stream().flatMap(s -> stopIds(s).stream()))
                .doesNotContain(isolated.toString());
    }

    @Test
    void aDestinationTheTravelerIsAlreadyLiveOnDropsOutOfEveryChain() {
        UUID a = createDestination("Athens", "Greece");
        UUID b = createDestination("Rome", "Italy");
        UUID c = createDestination("Paris", "France");
        createTransport(a, b, 90);
        createTransport(b, c, 120);
        UUID travelerId = UUID.randomUUID();

        // Subscribing to B removes it (and everything that only connects through it) from
        // the eligible-candidate set (docs/lets-travel-architecture-decisions.md §7: "not
        // already live for the traveler"), same rule a single recommendation follows.
        ResponseEntity<Map> subscribe = restTemplate.exchange(
                "/destinations/" + b + "/subscriptions", HttpMethod.POST,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)), Map.class);
        assertThat(subscribe.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> suggestions = getSuggestions(travelerId);

        assertThat(suggestions.stream().flatMap(s -> stopIds(s).stream()))
                .doesNotContain(b.toString());
    }

    @SuppressWarnings("unchecked")
    private List<String> stopIds(Map<String, Object> suggestion) {
        List<Map<String, Object>> stops = (List<Map<String, Object>>) suggestion.get("stops");
        return stops.stream().map(s -> (String) s.get("destinationId")).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getSuggestions(UUID travelerId) {
        ResponseEntity<List> response = restTemplate.exchange(
                "/travelers/me/itinerary-suggestions", HttpMethod.GET,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)), List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody();
    }

    private void createTransport(UUID from, UUID to, int durationMinutes) {
        Map<String, Object> body = Map.of("toDestinationId", to.toString(), "mode", "TRAIN", "durationMinutes", durationMinutes);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + from + "/transports", HttpMethod.POST, authorizedJsonEntity(body), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private UUID createDestination(String name, String country) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST,
                authorizedJsonEntity(Map.of(
                        "name", name, "country", country,
                        "startDate", LocalDate.now().plusDays(30).toString(),
                        "endDate", LocalDate.now().plusDays(35).toString(),
                        "managerId", UUID.randomUUID().toString(), "price", 0, "capacity", 50)),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private static HttpEntity<Map<String, Object>> authorizedJsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokens.validToken());
        return new HttpEntity<>(body, headers);
    }

    private static HttpEntity<Void> authorizedEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
