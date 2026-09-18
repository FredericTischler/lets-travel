package com.travelplan.travel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelplan.travel.config.ElasticsearchIndexInitializer;
import com.travelplan.travel.dto.AutocompleteSuggestion;
import com.travelplan.travel.dto.DestinationResponse;
import com.travelplan.travel.exception.DestinationNotFoundException;
import com.travelplan.travel.exception.SearchUnavailableException;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of the search feature: {@code GET /destinations/search} and
 * {@code GET /destinations/autocomplete} — see
 * docs/lets-travel-architecture-decisions.md §6.
 *
 * <p>{@link #search} runs a multi-field full-text query across name,
 * country, activities and accommodations ("across all travel details", per
 * the subject), then re-fetches each hit from {@link DestinationService}
 * (Neo4j, the source of truth) rather than trusting the Elasticsearch
 * document's own fields. Two reasons: (1) it reuses the already-tested
 * {@code DestinationResponse} building logic (full activities/accommodations
 * with their own ids) instead of duplicating it from a partial ES
 * projection, and (2) it is a second, independent guard against index drift
 * — {@link DestinationSearchIndexer} already removes a document at
 * soft-delete time, but if that removal itself had failed and was
 * swallowed, a stale hit here is silently dropped ({@code
 * DestinationNotFoundException} from the Neo4j re-fetch means the id no
 * longer resolves to an active destination) instead of leaking a
 * soft-deleted destination to the caller.</p>
 *
 * <p>{@link #autocomplete}, by contrast, is served directly from the
 * completion suggester's {@code _source} with NO Neo4j round-trip — see
 * {@link AutocompleteSuggestion}'s Javadoc for why (it is a live-typing UI
 * feature, "swift" per the subject). Soft-delete safety for autocomplete
 * relies solely on {@link DestinationSearchIndexer#remove} having removed
 * the document — the single mechanism the architecture decision names as
 * "automatic" for this case.</p>
 */
@Service
public class DestinationSearchService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final DestinationService destinationService;

    public DestinationSearchService(RestClient restClient, ObjectMapper objectMapper,
                                      DestinationService destinationService) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.destinationService = destinationService;
    }

    /**
     * Full-text search across name/country/activities/accommodations.
     * Results are ordered by Elasticsearch relevance score.
     *
     * @throws SearchUnavailableException if Elasticsearch cannot be reached
     */
    public List<DestinationResponse> search(String query) {
        Map<String, Object> body = Map.of(
                "query", Map.of("multi_match", Map.of(
                        "query", query,
                        "fields", List.of("name^3", "country^2", "activities", "accommodations"),
                        "fuzziness", "AUTO")),
                "size", 50);
        try {
            Response response = performSearch(body);
            List<UUID> ids = extractHitIds(response);
            List<DestinationResponse> results = new ArrayList<>();
            for (UUID id : ids) {
                try {
                    results.add(destinationService.findById(id));
                } catch (DestinationNotFoundException ex) {
                    // Index drift (see class Javadoc): silently drop rather than leak.
                }
            }
            return results;
        } catch (IOException ex) {
            throw new SearchUnavailableException("search", ex);
        }
    }

    /**
     * Prefix-based autocomplete via the Elasticsearch completion suggester
     * on the {@code suggest} field (input = name + country at index time),
     * NOT a Postgres/Cypher {@code LIKE}.
     *
     * @throws SearchUnavailableException if Elasticsearch cannot be reached
     */
    public List<AutocompleteSuggestion> autocomplete(String prefix) {
        Map<String, Object> body = Map.of(
                "suggest", Map.of("destination-suggest", Map.of(
                        "prefix", prefix,
                        "completion", Map.of("field", "suggest", "size", 10, "skip_duplicates", true))),
                "_source", List.of("id", "name", "country"));
        try {
            Response response = performSearch(body);
            return extractSuggestions(response);
        } catch (IOException ex) {
            throw new SearchUnavailableException("autocomplete", ex);
        }
    }

    private Response performSearch(Map<String, Object> body) throws IOException {
        Request request = new Request("POST", "/" + ElasticsearchIndexInitializer.INDEX_NAME + "/_search");
        request.setJsonEntity(objectMapper.writeValueAsString(body));
        return restClient.performRequest(request);
    }

    @SuppressWarnings("unchecked")
    private List<UUID> extractHitIds(Response response) throws IOException {
        Map<String, Object> json = objectMapper.readValue(response.getEntity().getContent(), Map.class);
        Map<String, Object> hitsWrapper = (Map<String, Object>) json.get("hits");
        List<Map<String, Object>> hits = (List<Map<String, Object>>) hitsWrapper.get("hits");
        List<UUID> ids = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            ids.add(UUID.fromString((String) hit.get("_id")));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private List<AutocompleteSuggestion> extractSuggestions(Response response) throws IOException {
        Map<String, Object> json = objectMapper.readValue(response.getEntity().getContent(), Map.class);
        Map<String, Object> suggestWrapper = (Map<String, Object>) json.get("suggest");
        List<AutocompleteSuggestion> results = new ArrayList<>();
        if (suggestWrapper == null) {
            return results;
        }
        List<Map<String, Object>> entries = (List<Map<String, Object>>) suggestWrapper.get("destination-suggest");
        for (Map<String, Object> entry : entries) {
            List<Map<String, Object>> options = (List<Map<String, Object>>) entry.get("options");
            for (Map<String, Object> option : options) {
                Map<String, Object> source = (Map<String, Object>) option.get("_source");
                UUID id = UUID.fromString((String) source.get("id"));
                results.add(new AutocompleteSuggestion(id, (String) source.get("name"), (String) source.get("country")));
            }
        }
        return results;
    }
}
