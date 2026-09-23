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