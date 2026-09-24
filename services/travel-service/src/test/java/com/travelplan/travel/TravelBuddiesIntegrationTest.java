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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Travel Buddies
 * (docs/lets-travel-architecture-decisions.md §12): the opt-in
 * {@code buddyVisible} flag on a {@code SUBSCRIBED} relation and
 * {@code GET /destinations/{id}/buddies}.
 *
 * Same structure as {@link SubscriptionIntegrationTest}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class TravelBuddiesIntegrationTest {

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
    void buddyVisibilityIsOffByDefault() {
        UUID destinationId = createDestination();
        UUID travelerId = UUID.randomUUID();
        subscribe(destinationId, travelerId);

        assertThat(getBuddies(destinationId, travelerId)).isEmpty();
    }

    @Test
    void optingInMakesATravelerVisibleToOtherLiveSubscribersOnly() {
        UUID destinationId = createDestination();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        subscribe(destinationId, alice);
        subscribe(destinationId, bob);
        subscribe(destinationId, carol);

        setVisibility(destinationId, alice, true, HttpStatus.NO_CONTENT);
        setVisibility(destinationId, bob, true, HttpStatus.NO_CONTENT);
        // carol never opts in.

        List<Map<String, Object>> bobSees = getBuddies(destinationId, bob);
        assertThat(bobSees).hasSize(1);
        assertThat(bobSees.get(0)).containsEntry("travelerId", alice.toString());

        List<Map<String, Object>> carolSees = getBuddies(destinationId, carol);
        assertThat(carolSees).extracting(m -> m.get("travelerId"))
                .containsExactlyInAnyOrder(alice.toString(), bob.toString());
    }

    @Test
    void aTravelerNeverSeesThemselfInTheirOwnBuddyList() {
        UUID destinationId = createDestination();
        UUID alice = UUID.randomUUID();
        subscribe(destinationId, alice);
        setVisibility(destinationId, alice, true, HttpStatus.NO_CONTENT);

        assertThat(getBuddies(destinationId, alice)).isEmpty();
    }

    @Test
    void optingOutRemovesVisibilityImmediately() {
        UUID destinationId = createDestination();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        subscribe(destinationId, alice);
        subscribe(destinationId, bob);

        setVisibility(destinationId, alice, true, HttpStatus.NO_CONTENT);
        assertThat(getBuddies(destinationId, bob)).hasSize(1);

        setVisibility(destinationId, alice, false, HttpStatus.NO_CONTENT);
        assertThat(getBuddies(destinationId, bob)).isEmpty();
    }

    @Test
    void nonSubscriberCannotSetVisibilityOrListBuddies() {
        UUID destinationId = createDestination();
        UUID outsider = UUID.randomUUID();

        setVisibility(destinationId, outsider, true, HttpStatus.NOT_FOUND);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/buddies", HttpMethod.GET,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", outsider)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aCancelledSubscriberIsNoLongerAVisibleBuddy() {
        UUID destinationId = createDestination();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        subscribe(destinationId, alice);
        subscribe(destinationId, bob);
        setVisibility(destinationId, alice, true, HttpStatus.NO_CONTENT);

        restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.DELETE,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", alice)), Void.class);

        assertThat(getBuddies(destinationId, bob)).isEmpty();
    }

    private void subscribe(UUID destinationId, UUID travelerId) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void setVisibility(UUID destinationId, UUID travelerId, boolean visible, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions/buddy-visibility", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("visible", visible), headers), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getBuddies(UUID destinationId, UUID travelerId) {
        ResponseEntity<List> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/buddies", HttpMethod.GET,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)), List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody() == null ? List.of() : (List<Map<String, Object>>) response.getBody();
    }

    private UUID createDestination() {
        UUID managerId = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "name", "Testville", "country", "Testland",
                "startDate", LocalDate.now().plusDays(30).toString(),
                "endDate", LocalDate.now().plusDays(35).toString(),
                "managerId", managerId.toString(), "price", 0, "capacity", 50);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private static HttpEntity<Void> authorizedEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
