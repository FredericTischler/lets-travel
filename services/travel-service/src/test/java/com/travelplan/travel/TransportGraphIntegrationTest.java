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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@code TRANSPORT} relationship (increment 2):
 * creation, one-hop traversal, and — the point that matters most for this
 * increment — that the traversal query filters {@code deletedAt IS NULL} at
 * the target hop, without ever DETACH DELETE-ing the relationship itself.
 *
 * Uses Testcontainers (neo4j:5.26.6-community, same image as production and
 * as {@code DestinationLifecycleIntegrationTest}). Every call below carries a
 * Bearer token (see {@link TestJwtTokens}), since every endpoint on
 * DestinationController/TransportController requires one.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransportGraphIntegrationTest {

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

    @Test
    void softDeletingTargetHidesItFromTraversalWithoutRemovingTheRelationship() {
        // Step 1 — create two destinations A and B
        UUID a = createDestination("Paris", "France");
        UUID b = createDestination("Lisbon", "Portugal");

        // Step 2 — POST /destinations/{A}/transports {toDestinationId: B, mode: TRAIN, durationMinutes: 120} -> 201
        Map<String, Object> transportBody = Map.of(
                "toDestinationId", b.toString(),
                "mode", "TRAIN",
                "durationMinutes", 120);
        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.POST,
                authorizedJsonEntity(transportBody), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).containsEntry("mode", "TRAIN");
        assertThat(createResponse.getBody()).containsEntry("durationMinutes", 120);
        assertThat(createResponse.getBody()).containsEntry("destinationId", b.toString());

        // Step 3 — GET /destinations/{A}/transports -> contains B
        ResponseEntity<List> beforeDelete = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.GET, authorizedEntity(), List.class);
        assertThat(beforeDelete.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(beforeDelete.getBody()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstHop = (Map<String, Object>) beforeDelete.getBody().get(0);
        assertThat(firstHop).containsEntry("destinationId", b.toString());

        // Step 4 — DELETE /destinations/{B} (soft-delete)
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/destinations/" + b, HttpMethod.DELETE, authorizedEntity(), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Step 5 — GET /destinations/{A}/transports -> no longer contains B,
        // even though the TRANSPORT relationship still exists in the graph
        // (no DETACH DELETE is ever issued by the soft-delete path).
        ResponseEntity<List> afterDelete = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.GET, authorizedEntity(), List.class);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterDelete.getBody()).isEmpty();
    }

    @Test
    void departureAndArrivalTimeAreOptionalScheduleDetailsCarriedThrough() {
        UUID a = createDestination("Barcelona", "Spain");
        UUID b = createDestination("Nice", "France");

        Map<String, Object> transportBody = Map.of(
                "toDestinationId", b.toString(),
                "mode", "PLANE",
                "durationMinutes", 75,
                "departureTime", "2026-05-01T08:30:00Z",
                "arrivalTime", "2026-05-01T09:45:00Z");
        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.POST,
                authorizedJsonEntity(transportBody), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).containsEntry("departureTime", "2026-05-01T08:30:00Z");
        assertThat(createResponse.getBody()).containsEntry("arrivalTime", "2026-05-01T09:45:00Z");

        ResponseEntity<List> listResponse = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.GET, authorizedEntity(), List.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> hop = (Map<String, Object>) listResponse.getBody().get(0);
        assertThat(hop).containsEntry("departureTime", "2026-05-01T08:30:00Z")
                .containsEntry("arrivalTime", "2026-05-01T09:45:00Z");
    }

    @Test
    void selfLoopIsRejected() {
        UUID a = createDestination("Rome", "Italy");

        Map<String, Object> body = Map.of("toDestinationId", a.toString(), "mode", "TRAIN", "durationMinutes", 30);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.POST, authorizedJsonEntity(body), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidModeIsRejected() {
        UUID a = createDestination("Madrid", "Spain");
        UUID b = createDestination("Berlin", "Germany");

        Map<String, Object> body = Map.of("toDestinationId", b.toString(), "mode", "TELEPORT", "durationMinutes", 30);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.POST, authorizedJsonEntity(body), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nonPositiveDurationIsRejected() {
        UUID a = createDestination("Vienna", "Austria");
        UUID b = createDestination("Prague", "Czechia");

        Map<String, Object> body = Map.of("toDestinationId", b.toString(), "mode", "BUS", "durationMinutes", 0);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.POST, authorizedJsonEntity(body), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingOriginReturnsNotFound() {
        UUID b = createDestination("Amsterdam", "Netherlands");

        Map<String, Object> body = Map.of("toDestinationId", b.toString(), "mode", "CAR", "durationMinutes", 30);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + UUID.randomUUID() + "/transports", HttpMethod.POST,
                authorizedJsonEntity(body), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingTargetReturnsNotFound() {
        UUID a = createDestination("Dublin", "Ireland");

        Map<String, Object> body = Map.of("toDestinationId", UUID.randomUUID().toString(), "mode", "PLANE", "durationMinutes", 30);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.POST, authorizedJsonEntity(body), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listingTransportsForMissingOriginReturnsNotFound() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + UUID.randomUUID() + "/transports", HttpMethod.GET, authorizedEntity(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updatingATransportReplacesItsFields() {
        UUID a = createDestination("Oslo", "Norway");
        UUID b = createDestination("Bergen", "Norway");
        UUID transportId = createTransport(a, b, "TRAIN", 420);

        Map<String, Object> update = Map.of("mode", "BUS", "durationMinutes", 480);
        ResponseEntity<Map> updateResponse = restTemplate.exchange(
                "/destinations/" + a + "/transports/" + transportId, HttpMethod.PUT,
                authorizedJsonEntity(update), Map.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody()).containsEntry("mode", "BUS").containsEntry("durationMinutes", 480);

        ResponseEntity<List> listResponse = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.GET, authorizedEntity(), List.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> hop = (Map<String, Object>) listResponse.getBody().get(0);
        assertThat(hop).containsEntry("mode", "BUS").containsEntry("durationMinutes", 480);
    }

    @Test
    void updatingAnUnknownTransportReturnsNotFound() {
        UUID a = createDestination("Helsinki", "Finland");

        Map<String, Object> update = Map.of("mode", "BUS", "durationMinutes", 60);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/transports/" + UUID.randomUUID(), HttpMethod.PUT,
                authorizedJsonEntity(update), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updatingATransportWhoseTargetWasSoftDeletedReturnsNotFound() {
        UUID a = createDestination("Vilnius", "Lithuania");
        UUID b = createDestination("Kaunas", "Lithuania");
        UUID transportId = createTransport(a, b, "BUS", 90);

        restTemplate.exchange("/destinations/" + b, HttpMethod.DELETE, authorizedEntity(), Void.class);

        Map<String, Object> update = Map.of("mode", "CAR", "durationMinutes", 100);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/transports/" + transportId, HttpMethod.PUT,
                authorizedJsonEntity(update), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidModeOnUpdateIsRejected() {
        UUID a = createDestination("Riga", "Latvia");
        UUID b = createDestination("Tallinn", "Estonia");
        UUID transportId = createTransport(a, b, "BUS", 240);

        Map<String, Object> update = Map.of("mode", "TELEPORT", "durationMinutes", 60);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/transports/" + transportId, HttpMethod.PUT,
                authorizedJsonEntity(update), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deletingATransportHidesItFromTheListAndIsIdempotentlyNotFoundAfterwards() {
        UUID a = createDestination("Krakow", "Poland");
        UUID b = createDestination("Warsaw", "Poland");
        UUID transportId = createTransport(a, b, "TRAIN", 150);

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/destinations/" + a + "/transports/" + transportId, HttpMethod.DELETE, authorizedEntity(), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List> listResponse = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.GET, authorizedEntity(), List.class);
        assertThat(listResponse.getBody()).isEmpty();

        // Deleting the same (now soft-deleted) transport again is a 404, not a second 204.
        ResponseEntity<Map> secondDelete = restTemplate.exchange(
                "/destinations/" + a + "/transports/" + transportId, HttpMethod.DELETE, authorizedEntity(), Map.class);
        assertThat(secondDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void findsARouteAcrossTwoHopsWhenNoDirectTransportExists() {
        UUID a = createDestination("Brussels", "Belgium");
        UUID b = createDestination("Cologne", "Germany");
        UUID c = createDestination("Frankfurt", "Germany");
        createTransport(a, b, "TRAIN", 100);
        createTransport(b, c, "TRAIN", 80);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/routes/" + c, HttpMethod.GET, authorizedEntity(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("totalDurationMinutes", 180);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops = (List<Map<String, Object>>) response.getBody().get("hops");
        assertThat(hops).hasSize(2);
        assertThat(hops.get(0)).containsEntry("destinationId", b.toString());
        assertThat(hops.get(1)).containsEntry("destinationId", c.toString());
    }

    @Test
    void routeIsNotFoundWhenAnIntermediateDestinationIsSoftDeleted() {
        UUID a = createDestination("Turin", "Italy");
        UUID b = createDestination("Milan", "Italy");
        UUID c = createDestination("Venice", "Italy");
        createTransport(a, b, "TRAIN", 60);
        createTransport(b, c, "TRAIN", 150);

        restTemplate.exchange("/destinations/" + b, HttpMethod.DELETE, authorizedEntity(), Void.class);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/routes/" + c, HttpMethod.GET, authorizedEntity(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void routeBetweenUnconnectedDestinationsIsNotFound() {
        UUID a = createDestination("Athens", "Greece");
        UUID b = createDestination("Sofia", "Bulgaria");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/routes/" + b, HttpMethod.GET, authorizedEntity(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void routeWithTheSameOriginAndTargetIsRejected() {
        UUID a = createDestination("Zagreb", "Croatia");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/routes/" + a, HttpMethod.GET, authorizedEntity(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void routeWithAMissingEndpointReturnsNotFound() {
        UUID a = createDestination("Ljubljana", "Slovenia");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + a + "/routes/" + UUID.randomUUID(), HttpMethod.GET, authorizedEntity(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Regression test for the Neo4jSchemaInitializer backfill: a TRANSPORT
     * relationship shaped like it predates the {@code id} property (created
     * directly, bypassing the API, the only way such a row could exist once
     * this application version's CREATE_QUERY — which always sets one — is
     * running) must not crash GET .../transports with an "Invalid UUID
     * string: null". Running the exact backfill statement here simulates
     * what happens once at the next application boot against real seeded data.
     */
    @Test
    void aTransportPredatingTheIdPropertyIsBackfilledAndNoLongerCrashesTheListing() {
        UUID a = createDestination("Graz", "Austria");
        UUID b = createDestination("Linz", "Austria");

        neoClientCreateLegacyTransport(a, b);

        neo4jClient.query("MATCH ()-[t:TRANSPORT]->() WHERE t.id IS NULL SET t.id = randomUUID()").run();

        ResponseEntity<List> response = restTemplate.exchange(
                "/destinations/" + a + "/transports", HttpMethod.GET, authorizedEntity(), List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> hop = (Map<String, Object>) response.getBody().get(0);
        assertThat(hop.get("id")).isNotNull();
    }

    private void neoClientCreateLegacyTransport(UUID from, UUID to) {
        neo4jClient.query("""
                        MATCH (origin:Destination), (target:Destination)
                        WHERE origin.id = $fromId AND target.id = $toId
                        CREATE (origin)-[:TRANSPORT {mode: 'TRAIN', durationMinutes: 60}]->(target)
                        """)
                .bindAll(Map.of("fromId", from.toString(), "toId", to.toString()))
                .run();
    }

    private UUID createTransport(UUID from, UUID to, String mode, int durationMinutes) {
        Map<String, Object> body = Map.of("toDestinationId", to.toString(), "mode", mode, "durationMinutes", durationMinutes);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + from + "/transports", HttpMethod.POST, authorizedJsonEntity(body), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
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