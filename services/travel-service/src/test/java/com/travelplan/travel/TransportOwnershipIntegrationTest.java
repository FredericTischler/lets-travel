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
 * Security audit G1: {@code POST /destinations/{fromId}/transports} is
 * ownership-aware. The link belongs to its origin destination, so only the
 * owning {@code TRAVEL_MANAGER} (or an {@code ADMIN}) may create it; the
 * target only has to exist (ADR "Transports" addendum). Real Neo4j through
 * Testcontainers, like the other travel-service integration tests.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransportOwnershipIntegrationTest {

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
    void owningManagerCanLinkTheirOwnDestinationToAnyExistingTarget() {
        UUID owner = UUID.randomUUID();
        UUID otherManager = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Lyon");
        UUID someoneElsesTarget = createAsManager(otherManager, "Turin");

        ResponseEntity<Map> response = postTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", owner), origin, someoneElsesTarget);

        // Origin ownership is what counts: the target being someone else's is allowed (ADR addendum).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("destinationId", someoneElsesTarget.toString());
    }

    @Test
    void anotherManagerCannotAttachATransportToSomeoneElsesDestination() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Geneva");
        UUID target = createAsManager(owner, "Zurich");

        ResponseEntity<Map> response = postTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", intruder), origin, target);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Not allowed to manage another manager's travel");
        assertThat(outgoingOf(origin)).isEmpty();
    }

    @Test
    void anotherManagerGetsTheSame403EvenWhenTheTargetDoesNotExist() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Basel");

        ResponseEntity<Map> response = postTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", intruder), origin, UUID.randomUUID());

        // Ownership is checked before the target lookup: a non-owner cannot probe which ids exist.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanLinkAnyManagersDestination() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Bern");
        UUID target = createAsManager(owner, "Lucerne");

        ResponseEntity<Map> response = postTransport(TestJwtTokens.validToken(), origin, target);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(outgoingOf(origin)).hasSize(1);
    }

    @Test
    void travelerCannotCreateATransportEvenOnADestinationIdMatchingTheirOwnSubject() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Annecy");
        UUID target = createAsManager(owner, "Chamonix");

        // A TRAVELER whose subject equals the destination's managerId is still refused: role check first.
        ResponseEntity<Map> response = postTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", owner), origin, target);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outgoingOf(origin)).isEmpty();
    }

    @Test
    void unknownOriginStillReturns404ForAManager() {
        UUID manager = UUID.randomUUID();
        UUID target = createAsManager(manager, "Nantes");

        ResponseEntity<Map> response = postTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", manager), UUID.randomUUID(), target);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ownerWithAnUnknownTargetGets404() {
        UUID manager = UUID.randomUUID();
        UUID origin = createAsManager(manager, "Rennes");

        ResponseEntity<Map> response = postTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", manager), origin, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherManagerCannotUpdateATransportOnSomeoneElsesOrigin() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Marseille");
        UUID target = createAsManager(owner, "Nice");
        UUID transportId = createTransportAsOwner(owner, origin, target);

        Map<String, Object> update = Map.of("mode", "CAR", "durationMinutes", 120);
        ResponseEntity<Map> response = putTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", intruder), origin, transportId, update);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherManagerCannotDeleteATransportOnSomeoneElsesOrigin() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Toulon");
        UUID target = createAsManager(owner, "Cannes");
        UUID transportId = createTransportAsOwner(owner, origin, target);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", intruder));
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + origin + "/transports/" + transportId, HttpMethod.DELETE,
                new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outgoingOf(origin)).hasSize(1);
    }

    @Test
    void ownerCanUpdateAndAdminCanDeleteTheSameTransport() {
        UUID owner = UUID.randomUUID();
        UUID origin = createAsManager(owner, "Dijon");
        UUID target = createAsManager(owner, "Besancon");
        UUID transportId = createTransportAsOwner(owner, origin, target);

        Map<String, Object> update = Map.of("mode", "CAR", "durationMinutes", 90);
        ResponseEntity<Map> updateResponse = putTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", owner), origin, transportId, update);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.validToken());
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/destinations/" + origin + "/transports/" + transportId, HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(outgoingOf(origin)).isEmpty();
    }

    private UUID createTransportAsOwner(UUID owner, UUID from, UUID to) {
        ResponseEntity<Map> response = postTransport(
                TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", owner), from, to);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private ResponseEntity<Map> putTransport(String token, UUID from, UUID transportId, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/destinations/" + from + "/transports/" + transportId, HttpMethod.PUT,
                new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> postTransport(String token, UUID from, UUID to) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("toDestinationId", to.toString(), "mode", "TRAIN", "durationMinutes", 90);
        return restTemplate.exchange(
                "/destinations/" + from + "/transports", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private List<?> outgoingOf(UUID origin) {
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
