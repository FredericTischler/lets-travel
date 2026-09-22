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
 * Integration tests for completing the {@code TRANSPORT} CRUD
 * (docs/lets-travel-architecture-decisions.md §11 addendum):
 * {@code PATCH}/{@code DELETE /destinations/{fromId}/transports/{transportId}}.
 * Same ownership rule and validation order as {@code create}
 * (form -> origin 404 -> ownership 403 -> transport 404), same physical
 * (non-soft) deletion. Real Neo4j through Testcontainers, same pattern as
 * {@link TransportOwnershipIntegrationTest}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransportCrudIntegrationTest {

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
    void owningManagerCanUpdateModeAndDuration() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Lille");
        UUID target = createAsManager(owner, "Amiens");
        UUID transportId = createTransport(owner, origin, target, "TRAIN", 60);

        Map<String, Object> body = Map.of("mode", "BUS", "durationMinutes", 90);
        ResponseEntity<Map> response = patchTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", owner), origin, transportId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("id", transportId.toString());
        assertThat(response.getBody()).containsEntry("mode", "BUS");
        assertThat(response.getBody()).containsEntry("durationMinutes", 90);

        List<Map<String, Object>> outgoing = outgoingOf(origin);
        assertThat(outgoing).hasSize(1);
        assertThat(outgoing.get(0)).containsEntry("mode", "BUS");
        assertThat(outgoing.get(0)).containsEntry("durationMinutes", 90);
    }

    @Test
    void updateCarriesDepartureAndArrivalTimeThrough() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Nancy");
        UUID target = createAsManager(owner, "Metz");
        UUID transportId = createTransport(owner, origin, target, "TRAIN", 30);

        Map<String, Object> body = Map.of(
                "mode", "TRAIN", "durationMinutes", 35,
                "departureTime", "2026-06-01T09:00:00Z", "arrivalTime", "2026-06-01T09:35:00Z");
        ResponseEntity<Map> response = patchTransport(TestJwtTokens.validToken(), origin, transportId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("departureTime", "2026-06-01T09:00:00Z");
        assertThat(response.getBody()).containsEntry("arrivalTime", "2026-06-01T09:35:00Z");
    }

    @Test
    void anotherManagerCannotUpdateSomeoneElsesTransport() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Reims");
        UUID target = createAsManager(owner, "Troyes");
        UUID transportId = createTransport(owner, origin, target, "TRAIN", 45);

        Map<String, Object> body = Map.of("mode", "BUS", "durationMinutes", 50);
        ResponseEntity<Map> response = patchTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", intruder), origin, transportId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Unchanged.
        assertThat(outgoingOf(origin).get(0)).containsEntry("mode", "TRAIN");
    }

    @Test
    void updatingAnUnknownTransportIdReturnsNotFound() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Dijon");

        Map<String, Object> body = Map.of("mode", "CAR", "durationMinutes", 20);
        ResponseEntity<Map> response = patchTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", owner), origin, UUID.randomUUID(), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updatingARealTransportIdThroughTheWrongOriginReturnsNotFound() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Orleans");
        UUID target = createAsManager(owner, "Tours");
        UUID otherOrigin = createAsManager(owner, "Blois");
        UUID transportId = createTransport(owner, origin, target, "TRAIN", 40);

        // transportId is real, but does not start from otherOrigin.
        Map<String, Object> body = Map.of("mode", "CAR", "durationMinutes", 20);
        ResponseEntity<Map> response = patchTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", owner), otherOrigin, transportId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidModeOnUpdateIsRejected() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Angers");
        UUID target = createAsManager(owner, "Rennes");
        UUID transportId = createTransport(owner, origin, target, "TRAIN", 40);

        Map<String, Object> body = Map.of("mode", "TELEPORT", "durationMinutes", 20);
        ResponseEntity<Map> response = patchTransport(TestJwtTokens.validToken(), origin, transportId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nonPositiveDurationOnUpdateIsRejected() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Caen");
        UUID target = createAsManager(owner, "Rouen");
        UUID transportId = createTransport(owner, origin, target, "TRAIN", 40);

        Map<String, Object> body = Map.of("mode", "TRAIN", "durationMinutes", 0);
        ResponseEntity<Map> response = patchTransport(TestJwtTokens.validToken(), origin, transportId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void adminCanUpdateAnyManagersTransport() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Grenoble");
        UUID target = createAsManager(owner, "Chambery");
        UUID transportId = createTransport(owner, origin, target, "TRAIN", 25);

        Map<String, Object> body = Map.of("mode", "CAR", "durationMinutes", 30);
        ResponseEntity<Map> response = patchTransport(TestJwtTokens.validToken(), origin, transportId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("mode", "CAR");
    }

    @Test
    void owningManagerCanDeleteTheirTransportPhysically() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Nantes");
        UUID target = createAsManager(owner, "La Rochelle");
        UUID transportId = createTransport(owner, origin, target, "BOAT", 200);

        ResponseEntity<Void> response = deleteTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", owner), origin, transportId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(outgoingOf(origin)).isEmpty();
    }

    @Test
    void anotherManagerCannotDeleteSomeoneElsesTransport() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Marseille");
        UUID target = createAsManager(owner, "Nice");
        UUID transportId = createTransport(owner, origin, target, "PLANE", 45);

        ResponseEntity<Void> response = deleteTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", intruder), origin, transportId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outgoingOf(origin)).hasSize(1);
    }

    @Test
    void deletingAnUnknownTransportIdReturnsNotFound() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Toulon");

        ResponseEntity<Void> response = deleteTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", owner), origin, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletingARealTransportIdThroughTheWrongOriginReturnsNotFound() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Pau");
        UUID target = createAsManager(owner, "Bayonne");
        UUID otherOrigin = createAsManager(owner, "Biarritz");
        UUID transportId = createTransport(owner, origin, target, "CAR", 60);

        ResponseEntity<Void> response = deleteTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", owner), otherOrigin, transportId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Untouched — still there via the real origin.
        assertThat(outgoingOf(origin)).hasSize(1);
    }

    @Test
    void deletingWithAMissingOriginReturnsNotFound() {
        ResponseEntity<Void> response = deleteTransport(TestJwtTokens.validToken(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminCanDeleteAnyManagersTransport() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Clermont-Ferrand");
        UUID target = createAsManager(owner, "Limoges");
        UUID transportId = createTransport(owner, origin, target, "BUS", 150);

        ResponseEntity<Void> response = deleteTransport(TestJwtTokens.validToken(), origin, transportId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(outgoingOf(origin)).isEmpty();
    }

    private ResponseEntity<Map> patchTransport(String token, UUID fromId, UUID transportId, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/destinations/" + fromId + "/transports/" + transportId, HttpMethod.PATCH,
                new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Void> deleteTransport(String token, UUID fromId, UUID transportId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(
                "/destinations/" + fromId + "/transports/" + transportId, HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);
    }

    private UUID createTransport(UUID managerId, UUID from, UUID to, String mode, int durationMinutes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("toDestinationId", to.toString(), "mode", mode, "durationMinutes", durationMinutes);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + from + "/transports", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private List<Map<String, Object>> outgoingOf(UUID origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.validToken());
        ResponseEntity<List> response = restTemplate.exchange(
                "/destinations/" + origin + "/transports", HttpMethod.GET, new HttpEntity<>(headers), List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private UUID createAsManager(UUID managerId, String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "name", name, "country", "Testland",
                "startDate", "2027-01-01", "endDate", "2027-01-05",
                "managerId", managerId.toString(), "price", 100.00, "capacity", 10);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }
}
