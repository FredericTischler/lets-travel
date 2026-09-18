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
 * Integration tests for the {@code SUBSCRIBED} relation
 * (docs/lets-travel-architecture-decisions.md §3): subscribe/unsubscribe to
 * a {@code Destination}, the 3-day self-service cutoff, and the
 * manager/admin-facing subscriber list and force-unsubscribe.
 *
 * Follows the exact structure of {@code TravelOwnershipIntegrationTest} and
 * {@code DestinationLifecycleIntegrationTest}: one Testcontainers Neo4j
 * instance per test class, one {@code TestRestTemplate} call per HTTP step.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class SubscriptionIntegrationTest {

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
    void travelerCanSubscribeToAFutureDestination() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createDestination(managerId, LocalDate.now().plusDays(30), LocalDate.now().plusDays(35));
        UUID travelerId = UUID.randomUUID();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("destinationId", destinationId.toString());
        assertThat(response.getBody()).containsEntry("travelerId", travelerId.toString());
        assertThat(response.getBody()).containsEntry("status", "ACTIVE");
    }

    @Test
    void subscribingTwiceWhileActiveIsRejected() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createDestination(managerId, LocalDate.now().plusDays(30), LocalDate.now().plusDays(35));
        String travelerToken = TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", UUID.randomUUID());

        ResponseEntity<Map> first = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST,
                authorizedEntity(travelerToken), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST,
                authorizedEntity(travelerToken), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void subscribingToAnUnknownDestinationIsNotFound() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + UUID.randomUUID() + "/subscriptions", HttpMethod.POST,
                authorizedEntity(TestJwtTokens.tokenWithRole("TRAVELER")), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void travelerCanUnsubscribeWellBeforeTheCutoff() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createDestination(managerId, LocalDate.now().plusDays(30), LocalDate.now().plusDays(35));
        String travelerToken = TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", UUID.randomUUID());

        restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST,
                authorizedEntity(travelerToken), Map.class);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.DELETE,
                authorizedEntity(travelerToken), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void unsubscribingLessThanThreeDaysBeforeStartIsRejected() {
        UUID managerId = UUID.randomUUID();
        // startDate tomorrow: well under the 3-day cutoff for self-service cancellation.
        UUID destinationId = createDestination(managerId, LocalDate.now().plusDays(1), LocalDate.now().plusDays(4));
        String travelerToken = TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", UUID.randomUUID());

        restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST,
                authorizedEntity(travelerToken), Map.class);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.DELETE,
                authorizedEntity(travelerToken), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unsubscribingWithNoActiveSubscriptionIsNotFound() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createDestination(managerId, LocalDate.now().plusDays(30), LocalDate.now().plusDays(35));
        String travelerToken = TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", UUID.randomUUID());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.DELETE,
                authorizedEntity(travelerToken), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void owningManagerCanListSubscribersForTheirOwnDestination() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createDestination(managerId, LocalDate.now().plusDays(30), LocalDate.now().plusDays(35));
        UUID travelerId = UUID.randomUUID();
        restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)), Map.class);

        ResponseEntity<List> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.GET,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId)), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) response.getBody().get(0);
        assertThat(row).containsEntry("travelerId", travelerId.toString());
        assertThat(row).containsEntry("status", "ACTIVE");
    }

    @Test
    void anotherManagerCannotListSubscribersForSomeoneElsesDestination() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID destinationId = createDestination(owner, LocalDate.now().plusDays(30), LocalDate.now().plusDays(35));

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.GET,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", intruder)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherManagerCannotForceUnsubscribeFromSomeoneElsesDestination() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID destinationId = createDestination(owner, LocalDate.now().plusDays(30), LocalDate.now().plusDays(35));
        UUID travelerId = UUID.randomUUID();
        restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)), Map.class);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions/" + travelerId, HttpMethod.DELETE,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", intruder)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void owningManagerCanForceUnsubscribeATravelerEvenWithinTheCutoff() {
        UUID managerId = UUID.randomUUID();
        // startDate tomorrow: would be rejected for self-service (see
        // unsubscribingLessThanThreeDaysBeforeStartIsRejected), but the
        // manager force-unsubscribe path deliberately does not enforce the
        // cutoff (see SubscriptionService's Javadoc).
        UUID destinationId = createDestination(managerId, LocalDate.now().plusDays(1), LocalDate.now().plusDays(4));
        UUID travelerId = UUID.randomUUID();
        restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)), Map.class);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions/" + travelerId, HttpMethod.DELETE,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void travelerCanSeeTheirOwnSubscriptionHistory() {
        UUID managerId = UUID.randomUUID();
        UUID destinationId = createDestination(managerId, LocalDate.now().plusDays(30), LocalDate.now().plusDays(35));
        UUID travelerId = UUID.randomUUID();
        String travelerToken = TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId);
        restTemplate.exchange(
                "/destinations/" + destinationId + "/subscriptions", HttpMethod.POST,
                authorizedEntity(travelerToken), Map.class);

        ResponseEntity<List> response = restTemplate.exchange(
                "/travelers/me/subscriptions", HttpMethod.GET, authorizedEntity(travelerToken), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) response.getBody().get(0);
        assertThat(row).containsEntry("destinationId", destinationId.toString());
        assertThat(row).containsEntry("status", "ACTIVE");
    }

    private UUID createDestination(UUID managerId, LocalDate startDate, LocalDate endDate) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "name", "Testville", "country", "Testland",
                "startDate", startDate.toString(), "endDate", endDate.toString(),
                "managerId", managerId.toString(), "price", 100.00, "capacity", 10);
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
