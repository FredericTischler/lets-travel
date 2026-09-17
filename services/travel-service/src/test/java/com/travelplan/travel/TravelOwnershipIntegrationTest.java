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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the ownership-aware mutation introduced in
 * docs/lets-travel-architecture-decisions.md §2: a destination (the
 * subject's "Travel" entity) carries a {@code managerId}, and only its own
 * manager — or an {@code ADMIN} — may update or delete it. Creation in
 * someone else's name is covered by {@link TravelCatalogueRbacIntegrationTest}.
 *
 * Uses Testcontainers (neo4j:5.26.6-community, same image as production and
 * as the other travel-service integration tests).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class TravelOwnershipIntegrationTest {

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
    void owningManagerCanUpdateTheirOwnTravel() {
        UUID managerId = UUID.randomUUID();
        UUID id = createAsManager(managerId, "Prague");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + id, HttpMethod.PUT, updateEntity(managerId, "Prague Extended"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("name", "Prague Extended");
    }

    @Test
    void anotherManagerCannotUpdateSomeoneElsesTravel() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID id = createAsManager(owner, "Vienna");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + id, HttpMethod.PUT, updateEntity(intruder, "Vienna Hijacked"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Not allowed to manage another manager's travel");
    }

    @Test
    void anotherManagerCannotDeleteSomeoneElsesTravel() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID id = createAsManager(owner, "Budapest");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", intruder));
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + id, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanUpdateAndDeleteAnyManagersTravel() {
        UUID owner = UUID.randomUUID();
        UUID id = createAsManager(owner, "Krakow");

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(TestJwtTokens.validToken());
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> updateBody = Map.of(
                "name", "Krakow Redux", "country", "Poland",
                "startDate", "2027-03-01", "endDate", "2027-03-05",
                "price", 150.00, "capacity", 40);
        ResponseEntity<Map> updateResponse = restTemplate.exchange(
                "/destinations/" + id, HttpMethod.PUT, new HttpEntity<>(updateBody, adminHeaders), Map.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/destinations/" + id, HttpMethod.DELETE, new HttpEntity<>(adminHeaders), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
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

    private HttpEntity<Map<String, Object>> updateEntity(UUID callerId, String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtTokens.tokenWithRoleAndSubject("TRAVEL_MANAGER", callerId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "name", name, "country", "Testland",
                "startDate", "2027-01-01", "endDate", "2027-01-06",
                "price", 120.00, "capacity", 12);
        return new HttpEntity<>(body, headers);
    }
}
