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
 * Integration tests for the 3-role split introduced in
 * docs/lets-travel-architecture-decisions.md §1: reads are open to any known
 * role, mutation is restricted to ADMIN/TRAVEL_MANAGER.
 *
 * Uses Testcontainers (neo4j:5.26.6-community, same image as production and
 * as the other travel-service integration tests).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class TravelCatalogueRbacIntegrationTest {

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
    void travelerCanListDestinations() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRole("TRAVELER"));

        ResponseEntity<List> response = restTemplate.exchange(
                "/destinations", HttpMethod.GET, new HttpEntity<>(headers), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static Map<String, Object> destinationPayload(String name, UUID managerId) {
        return Map.of(
                "name", name,
                "country", "France",
                "startDate", "2027-06-01",
                "endDate", "2027-06-10",
                "managerId", managerId.toString(),
                "price", 199.00,
                "capacity", 25);
    }

    @Test
    void travelerCannotCreateADestination() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRole("TRAVELER"));
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST,
                new HttpEntity<>(destinationPayload("Lyon", UUID.randomUUID()), headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Administrator or Travel Manager role required");
    }

    @Test
    void travelManagerCanCreateADestinationInTheirOwnName() {
        UUID managerId = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", managerId));
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST,
                new HttpEntity<>(destinationPayload("Marseille", managerId), headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("managerId", managerId.toString());
    }

    @Test
    void travelManagerCannotCreateADestinationInSomeoneElsesName() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRole("TRAVEL_MANAGER"));
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST,
                new HttpEntity<>(destinationPayload("Nantes", UUID.randomUUID()), headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Not allowed to manage another manager's travel");
    }
}
