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
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the Elasticsearch-backed search/autocomplete
 * endpoints (docs/lets-travel-architecture-decisions.md §6):
 * {@code GET /destinations/search} and {@code GET /destinations/autocomplete}.
 *
 * Real Elasticsearch via Testcontainers (matches the production image tag,
 * see ansible/roles/elasticsearch/defaults/main.yml) — same
 * Testcontainers-first convention as {@link DestinationLifecycleIntegrationTest}'s
 * Neo4j container, adapted to a second container since this feature spans
 * both stores (Neo4j is still the source of truth the search endpoint reads
 * back from, see {@code DestinationSearchService}).
 *
 * The indexer writes with {@code refresh=wait_for} (see
 * {@code DestinationSearchIndexer}), so results are visible to search
 * immediately after the create call returns — no polling/sleeping needed
 * here.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class DestinationSearchIntegrationTest {

    @Container
    static final Neo4jContainer<?> neo4j =
            new Neo4jContainer<>("neo4j:5.26.6-community")
                    .withAdminPassword("test_password_only");

    @Container
    static final ElasticsearchContainer elasticsearch =
            new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("xpack.security.http.ssl.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("NEO4J_HOST", neo4j::getHost);
        registry.add("NEO4J_PORT", () -> String.valueOf(neo4j.getMappedPort(7687)));
        registry.add("NEO4J_USERNAME", () -> "neo4j");
        registry.add("NEO4J_PASSWORD", neo4j::getAdminPassword);
        registry.add("JWT_SIGNING_KEY", () -> TestJwtTokens.SIGNING_KEY);
        registry.add("ES_HOST", elasticsearch::getHost);
        registry.add("ES_PORT", () -> String.valueOf(elasticsearch.getMappedPort(9200)));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void searchReturnsANewlyCreatedDestinationAcrossItsDetails() {
        Map<String, Object> createBody = Map.of(
                "name", "Reykjavik Northern Lights", "country", "Iceland",
                "startDate", "2026-11-01", "endDate", "2026-11-05",
                "managerId", UUID.randomUUID().toString(), "price", 899.00, "capacity", 15,
                "activities", List.of("Aurora borealis tour", "Blue Lagoon"),
                "accommodations", List.of(Map.of("name", "Hotel Borg", "type", "HOTEL")));
        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/destinations", HttpMethod.POST, authorizedJsonEntity(createBody), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) createResponse.getBody().get("id"));

        // Full-text query on an ACTIVITY name, not the destination name itself —
        // proves "across all travel details" (activities/accommodations included).
        ResponseEntity<List> searchResponse = restTemplate.exchange(
                "/destinations/search?q=Aurora+borealis", HttpMethod.GET, authorizedEntity(), List.class);
        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = searchResponse.getBody();
        assertThat(hits).anyMatch(hit -> id.toString().equals(hit.get("id")));
    }

    @Test
    void autocompleteReturnsPrefixMatchesOnName() {
        Map<String, Object> createBody = Map.of(
                "name", "Marrakech Souks Tour", "country", "Morocco",
                "startDate", "2026-12-01", "endDate", "2026-12-04",
                "managerId", UUID.randomUUID().toString(), "price", 349.00, "capacity", 10);
        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/destinations", HttpMethod.POST, authorizedJsonEntity(createBody), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) createResponse.getBody().get("id"));

        ResponseEntity<List> autocompleteResponse = restTemplate.exchange(
                "/destinations/autocomplete?prefix=Marrak", HttpMethod.GET, authorizedEntity(), List.class);
        assertThat(autocompleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = autocompleteResponse.getBody();
        assertThat(suggestions).anyMatch(s -> id.toString().equals(s.get("id")));
    }

    @Test
    void softDeletedDestinationNeverAppearsInSearchOrAutocomplete() {
        Map<String, Object> createBody = Map.of(
                "name", "Zanzibar Spice Route", "country", "Tanzania",
                "startDate", "2026-10-01", "endDate", "2026-10-06",
                "managerId", UUID.randomUUID().toString(), "price", 749.00, "capacity", 12,
                "activities", List.of("Spice farm visit"));
        ResponseEntity<Map> createResponse = restTemplate.exchange(
                "/destinations", HttpMethod.POST, authorizedJsonEntity(createBody), Map.class);
        UUID id = UUID.fromString((String) createResponse.getBody().get("id"));

        // Sanity check: it IS findable before deletion.
        ResponseEntity<List> beforeDelete = restTemplate.exchange(
                "/destinations/search?q=Zanzibar", HttpMethod.GET, authorizedEntity(), List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hitsBefore = beforeDelete.getBody();
        assertThat(hitsBefore).anyMatch(hit -> id.toString().equals(hit.get("id")));

        restTemplate.exchange("/destinations/" + id, HttpMethod.DELETE, authorizedEntity(), Void.class);

        ResponseEntity<List> afterDeleteSearch = restTemplate.exchange(
                "/destinations/search?q=Zanzibar", HttpMethod.GET, authorizedEntity(), List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hitsAfter = afterDeleteSearch.getBody();
        assertThat(hitsAfter).noneMatch(hit -> id.toString().equals(hit.get("id")));

        ResponseEntity<List> afterDeleteAutocomplete = restTemplate.exchange(
                "/destinations/autocomplete?prefix=Zanzibar", HttpMethod.GET, authorizedEntity(), List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestionsAfter = afterDeleteAutocomplete.getBody();
        assertThat(suggestionsAfter).noneMatch(s -> id.toString().equals(s.get("id")));
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
