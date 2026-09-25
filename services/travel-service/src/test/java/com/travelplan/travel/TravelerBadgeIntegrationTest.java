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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code GET /travelers/me/badges} (bonus feature,
 * docs/lets-travel-architecture-decisions.md §12): countries/destinations
 * visited and reviews given, seeded straight into the graph like
 * {@link FeedbackIntegrationTest} (the public API cannot create a past,
 * already-participated subscription).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class TravelerBadgeIntegrationTest {

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
    void aTravelerWithNoHistoryHasZeroedCountsAndNoBadgeEarned() {
        Map<String, Object> body = getBadges(UUID.randomUUID());

        assertThat(body)
                .containsEntry("destinationsVisited", 0)
                .containsEntry("countriesVisited", 0)
                .containsEntry("reviewsGiven", 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> badges = (List<Map<String, Object>>) body.get("badges");
        assertThat(badges).isNotEmpty().allSatisfy(b -> assertThat(b).containsEntry("earned", false));
    }

    @Test
    void oneEndedActiveDestinationEarnsExplorerButNotGlobetrotter() {
        UUID travelerId = UUID.randomUUID();
        seedPastParticipation(travelerId, "Kyoto", "Japan");

        Map<String, Object> body = getBadges(travelerId);

        assertThat(body).containsEntry("destinationsVisited", 1).containsEntry("countriesVisited", 1);
        assertThat(earned(body, "EXPLORER")).isTrue();
        assertThat(earned(body, "GLOBETROTTER")).isFalse();
    }

    @Test
    void fiveDistinctCountriesEarnGlobetrotter() {
        UUID travelerId = UUID.randomUUID();
        seedPastParticipation(travelerId, "Kyoto", "Japan");
        seedPastParticipation(travelerId, "Lima", "Peru");
        seedPastParticipation(travelerId, "Nairobi", "Kenya");
        seedPastParticipation(travelerId, "Oslo", "Norway");
        seedPastParticipation(travelerId, "Hanoi", "Vietnam");

        Map<String, Object> body = getBadges(travelerId);

        assertThat(body).containsEntry("countriesVisited", 5);
        assertThat(earned(body, "GLOBETROTTER")).isTrue();
    }

    @Test
    void aPendingOrCancelledSubscriptionDoesNotCountAsVisited() {
        UUID travelerId = UUID.randomUUID();
        UUID destinationId = createDestination("Riga", "Latvia", LocalDate.now().minusDays(60));
        seedSubscription(travelerId, destinationId, "CANCELLED");

        Map<String, Object> body = getBadges(travelerId);

        assertThat(body).containsEntry("destinationsVisited", 0);
    }

    @Test
    void anActiveSubscriptionOnATripNotYetOverDoesNotCountAsVisited() {
        UUID travelerId = UUID.randomUUID();
        UUID destinationId = createDestination("Tallinn", "Estonia", LocalDate.now().plusDays(30));
        seedSubscription(travelerId, destinationId, "ACTIVE");

        Map<String, Object> body = getBadges(travelerId);

        assertThat(body).containsEntry("destinationsVisited", 0);
    }

    @SuppressWarnings("unchecked")
    private boolean earned(Map<String, Object> body, String code) {
        List<Map<String, Object>> badges = (List<Map<String, Object>>) body.get("badges");
        return badges.stream().anyMatch(b -> code.equals(b.get("code")) && Boolean.TRUE.equals(b.get("earned")));
    }

    private void seedPastParticipation(UUID travelerId, String name, String country) {
        UUID destinationId = createDestination(name, country, LocalDate.now().minusDays(60));
        seedSubscription(travelerId, destinationId, "ACTIVE");
    }

    private void seedSubscription(UUID travelerId, UUID destinationId, String status) {
        neo4jClient.query("""
                        MATCH (d:Destination) WHERE d.id = $destinationId
                        MERGE (t:TravelerRef {userId: $travelerId})
                        CREATE (t)-[:SUBSCRIBED {id: randomUUID(), status: $status, subscribedAt: datetime()}]->(d)
                        """)
                .bindAll(Map.of("travelerId", travelerId.toString(), "destinationId", destinationId.toString(), "status", status))
                .run();
    }

    private Map<String, Object> getBadges(UUID travelerId) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/travelers/me/badges", HttpMethod.GET,
                authorizedEntity(TestJwtTokens.tokenWithRoleAndSubject("TRAVELER", travelerId)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** Destination whose {@code startDate}/{@code endDate} both land 5 days before {@code endDate}. */
    private UUID createDestination(String name, String country, LocalDate endDate) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST,
                authorizedJsonEntity(Map.of(
                        "name", name, "country", country,
                        "startDate", endDate.minusDays(5).toString(), "endDate", endDate.toString(),
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
