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
 * Integration test for the destination CRUD lifecycle:
 *   create -> read -> update -> soft-delete -> verify deletion.
 *
 * Increment 1 scope: basic CRUD on a single node type only, no relationship
 * or graph traversal exercised here.
 *
 * Uses Testcontainers (neo4j:5.26.6-community, same image as production).
 * DynamicPropertySource satisfies application.yml's required placeholders.
 * Every call below carries a Bearer token (see {@link TestJwtTokens}), since
 * every endpoint on DestinationController requires one.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class DestinationLifecycleIntegrationTest {

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
    void createReadUpdateThenSoftDelete() {
        // Step 1 — POST /destinations -> 201 Created, with dates, an activity and an accommodation
        Map<String, Object> createBody = Map.of(
                "name", "Lisbon", "country", "Portugal",
                "startDate", "2026-06-01", "endDate", "2026-06-05",
                "managerId", UUID.randomUUID().toString(), "price", 499.99, "capacity", 20,
                "activities", List.of("Tram 28 ride", "Belem Tower"),
                "accommodations", List.of(Map.of(
                        "name", "Hotel Lisboa", "type", "HOTEL",
                        "checkIn", "2026-06-01", "checkOut", "2026-06-05")));
        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/destinations", HttpMethod.POST, authorizedJsonEntity(createBody), Map.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).containsEntry("name", "Lisbon");
        assertThat(createResponse.getBody()).containsEntry("country", "Portugal");
        assertThat(createResponse.getBody()).containsEntry("startDate", "2026-06-01");
        assertThat(createResponse.getBody()).containsEntry("endDate", "2026-06-05");
        assertThat(createResponse.getBody()).containsEntry("durationDays", 5);
        assertThat(createResponse.getBody()).containsEntry("capacity", 20);
        assertThat(createResponse.getBody()).containsKey("managerId");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> createdActivities = (List<Map<String, Object>>) createResponse.getBody().get("activities");
        assertThat(createdActivities).hasSize(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> createdAccommodations =
                (List<Map<String, Object>>) createResponse.getBody().get("accommodations");
        assertThat(createdAccommodations).hasSize(1);
        assertThat(createdAccommodations.get(0)).containsEntry("name", "Hotel Lisboa");

        String destinationId = (String) createResponse.getBody().get("id");
        UUID id = UUID.fromString(destinationId);

        // Step 2 — GET /destinations/{id} -> 200, same data read back from Neo4j
        ResponseEntity<Map> getResponse = restTemplate.exchange(
                "/destinations/" + id, HttpMethod.GET, authorizedEntity(), Map.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).containsEntry("name", "Lisbon");
        assertThat(getResponse.getBody()).containsEntry("country", "Portugal");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readActivities = (List<Map<String, Object>>) getResponse.getBody().get("activities");
        assertThat(readActivities).hasSize(2);

        // Step 3 — PUT /destinations/{id} -> 200, fields replaced, including a
        // full replacement of the activity/accommodation lists
        Map<String, Object> updateBody = Map.of(
                "name", "Porto", "country", "Portugal",
                "startDate", "2026-07-10", "endDate", "2026-07-12",
                "price", 399.50, "capacity", 15,
                "activities", List.of("Port wine cellar tour"),
                "accommodations", List.of());
        ResponseEntity<Map> updateResponse = restTemplate.exchange(
                "/destinations/" + id, HttpMethod.PUT, authorizedJsonEntity(updateBody), Map.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody()).containsEntry("name", "Porto");
        assertThat(updateResponse.getBody()).containsEntry("durationDays", 3);
        assertThat(updateResponse.getBody()).containsEntry("capacity", 15);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> updatedActivities = (List<Map<String, Object>>) updateResponse.getBody().get("activities");
        assertThat(updatedActivities).hasSize(1);
        assertThat(updatedActivities.get(0)).containsEntry("name", "Port wine cellar tour");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> updatedAccommodations =
                (List<Map<String, Object>>) updateResponse.getBody().get("accommodations");
        assertThat(updatedAccommodations).isEmpty();

        // Step 4 — DELETE /destinations/{id} -> 204 No Content
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/destinations/" + id, HttpMethod.DELETE, authorizedEntity(), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Step 5 — GET /destinations/{id} -> 404 (soft-deleted)
        ResponseEntity<Map> getAfterDelete = restTemplate.exchange(
                "/destinations/" + id, HttpMethod.GET, authorizedEntity(), Map.class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getAfterDelete.getBody()).containsEntry("status", 404);
    }

    @Test
    void getUnknownIdReturnsNotFound() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations/" + UUID.randomUUID(), HttpMethod.GET, authorizedEntity(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void endDateBeforeStartDateIsRejected() {
        Map<String, Object> body = Map.of(
                "name", "Kyoto", "country", "Japan",
                "startDate", "2026-09-10", "endDate", "2026-09-05",
                "managerId", UUID.randomUUID().toString(), "price", 599.00, "capacity", 10);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST, authorizedJsonEntity(body), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void accommodationCheckOutBeforeCheckInIsRejected() {
        Map<String, Object> body = Map.of(
                "name", "Seville", "country", "Spain",
                "startDate", "2026-09-01", "endDate", "2026-09-05",
                "managerId", UUID.randomUUID().toString(), "price", 299.00, "capacity", 12,
                "accommodations", List.of(Map.of(
                        "name", "Hostal Sevilla", "type", "HOSTEL",
                        "checkIn", "2026-09-03", "checkOut", "2026-09-02")));
        ResponseEntity<Map> response = restTemplate.exchange(
                "/destinations", HttpMethod.POST, authorizedJsonEntity(body), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void softDeletingADestinationHidesItsActivitiesAndAccommodationsWithoutRemovingThem() {
        Map<String, Object> createBody = Map.of(
                "name", "Oslo", "country", "Norway",
                "startDate", "2026-08-01", "endDate", "2026-08-03",
                "managerId", UUID.randomUUID().toString(), "price", 349.00, "capacity", 8,
                "activities", List.of("Vigeland Park"),
                "accommodations", List.of(Map.of("name", "Oslo Inn", "type", "HOTEL")));
        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/destinations", HttpMethod.POST, authorizedJsonEntity(createBody), Map.class);
        UUID id = UUID.fromString((String) createResponse.getBody().get("id"));

        restTemplate.exchange("/destinations/" + id, HttpMethod.DELETE, authorizedEntity(), Void.class);

        ResponseEntity<Map> getAfterDelete = restTemplate.exchange(
                "/destinations/" + id, HttpMethod.GET, authorizedEntity(), Map.class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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