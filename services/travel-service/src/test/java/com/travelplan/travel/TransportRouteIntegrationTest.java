package com.travelplan.travel;

import com.travelplan.travel.support.TestJwtTokens;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code GET /destinations/{fromId}/routes/{toId}}
 * (docs/lets-travel-architecture-decisions.md §11, bonus): multi-hop
 * pathfinding over {@code TRANSPORT} relationships via {@code Neo4jClient}
 * (SDN has no pathfinding). Real Neo4j through Testcontainers, same pattern
 * as {@link TransportGraphIntegrationTest}.
 *
 * The point that matters most here: {@code deletedAt IS NULL} is checked on
 * EVERY node of the path, not just the two endpoints — a soft-deleted
 * intermediate destination must make the path untraversable even though its
 * own relationships are never physically removed.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransportRouteIntegrationTest {

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

    @Test
    void directOneHopPathIsFound() {
        UUID a = createDestination("Lyon", "France");
        UUID b = createDestination("Turin", "Italy");
        createTransport(a, b, "TRAIN", 120);

        ResponseEntity<Map> response = getRoute(a, b, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("reachable", true);
        assertThat(response.getBody()).containsEntry("totalDurationMinutes", 120);
        List<Map<String, Object>> hops = (List<Map<String, Object>>) response.getBody().get("hops");
        assertThat(hops).hasSize(1);
        assertThat(hops.get(0)).containsEntry("destinationId", b.toString());
        assertThat(hops.get(0)).containsEntry("mode", "TRAIN");
        assertThat(hops.get(0)).containsEntry("durationMinutes", 120);
    }

    @Test
    void multiHopPathIsFoundInOrderWithSummedDuration() {
        UUID a = createDestination("Paris", "France");
        UUID b = createDestination("Geneva", "Switzerland");
        UUID c = createDestination("Milan", "Italy");
        createTransport(a, b, "TRAIN", 180);
        createTransport(b, c, "BUS", 240);

        ResponseEntity<Map> response = getRoute(a, c, 4);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("reachable", true);
        assertThat(response.getBody()).containsEntry("totalDurationMinutes", 420);
        List<Map<String, Object>> hops = (List<Map<String, Object>>) response.getBody().get("hops");
        assertThat(hops).hasSize(2);
        assertThat(hops.get(0)).containsEntry("destinationId", b.toString());
        assertThat(hops.get(1)).containsEntry("destinationId", c.toString());
    }

    @Test
    void noConnectingTransportIsUnreachableNotNotFound() {
        UUID a = createDestination("Oslo", "Norway");
        UUID b = createDestination("Reykjavik", "Iceland");

        ResponseEntity<Map> response = getRoute(a, b, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("reachable", false);
        assertThat(response.getBody()).containsEntry("hops", List.of());
        assertThat(response.getBody().get("totalDurationMinutes")).isNull();
    }

    @Test
    void pathLongerThanMaxHopsIsUnreachable() {
        UUID a = createDestination("Brussels", "Belgium");
        UUID b = createDestination("Cologne", "Germany");
        UUID c = createDestination("Frankfurt", "Germany");
        UUID d = createDestination("Munich", "Germany");
        createTransport(a, b, "TRAIN", 60);
        createTransport(b, c, "TRAIN", 90);
        createTransport(c, d, "TRAIN", 120);

        // A->D only exists in 3 hops; maxHops=2 must not find it.
        ResponseEntity<Map> response = getRoute(a, d, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("reachable", false);
    }

    @Test
    void pathWithinMaxHopsIsFoundOnceRaised() {
        UUID a = createDestination("Ghent", "Belgium");
        UUID b = createDestination("Liege", "Belgium");
        UUID c = createDestination("Luxembourg", "Luxembourg");
        UUID d = createDestination("Strasbourg", "France");
        createTransport(a, b, "TRAIN", 40);
        createTransport(b, c, "TRAIN", 50);
        createTransport(c, d, "TRAIN", 70);

        ResponseEntity<Map> response = getRoute(a, d, 3);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("reachable", true);
        assertThat(response.getBody()).containsEntry("totalDurationMinutes", 160);
    }

    @Test
    void softDeletedIntermediateDestinationBreaksThePath() {
        UUID a = createDestination("Bordeaux", "France");
        UUID b = createDestination("Toulouse", "France");
        UUID c = createDestination("Montpellier", "France");
        createTransport(a, b, "TRAIN", 90);
        createTransport(b, c, "TRAIN", 60);

        // Sanity check: reachable before the intermediate is soft-deleted.
        assertThat(getRoute(a, c, null).getBody()).containsEntry("reachable", true);

        ResponseEntity<Void> deleteB = restTemplate.exchange(
                "/destinations/" + b, HttpMethod.DELETE, authorizedEntity(), Void.class);
        assertThat(deleteB.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> response = getRoute(a, c, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("reachable", false);
    }

    @Test
    void zeroMaxHopsIsRejected() {
        UUID a = createDestination("Porto", "Portugal");
        UUID b = createDestination("Vigo", "Spain");

        ResponseEntity<Map> response = getRoute(a, b, 0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void negativeMaxHopsIsRejected() {
        UUID a = createDestination("Kraków", "Poland");
        UUID b = createDestination("Warsaw", "Poland");

        ResponseEntity<Map> response = getRoute(a, b, -1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void maxHopsAboveCeilingIsRejected() {
        UUID a = createDestination("Riga", "Latvia");
        UUID b = createDestination("Tallinn", "Estonia");

        ResponseEntity<Map> response = getRoute(a, b, 7);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownOriginReturnsNotFound() {
        UUID b = createDestination("Sofia", "Bulgaria");

        ResponseEntity<Map> response = getRoute(UUID.randomUUID(), b, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownTargetReturnsNotFound() {
        UUID a = createDestination("Athens", "Greece");

        ResponseEntity<Map> response = getRoute(a, UUID.randomUUID(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<Map> getRoute(UUID from, UUID to, Integer maxHops) {
        String url = "/destinations/" + from + "/routes/" + to + (maxHops == null ? "" : "?maxHops=" + maxHops);
        return restTemplate.exchange(url, HttpMethod.GET, authorizedEntity(), Map.class);
    }

    private void createTransport(UUID from, UUID to, String mode, int durationMinutes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.validToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("toDestinationId", to.toString(), "mode", mode, "durationMinutes", durationMinutes);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + from + "/transports", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private UUID createDestination(String name, String country) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST,
                authorizedJsonEntity(Map.of(
                        "name", name, "country", country,
                        "startDate", "2026-05-01", "endDate", "2026-05-03",
                        "managerId", UUID.randomUUID().toString(), "price", 250.00, "capacity", 30)),
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

    private static HttpEntity<Void> authorizedEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.validToken());
        return new HttpEntity<>(headers);
    }
}
