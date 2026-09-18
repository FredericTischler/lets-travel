package com.travelplan.travel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelplan.travel.config.ElasticsearchIndexInitializer;
import com.travelplan.travel.dto.AccommodationResponse;
import com.travelplan.travel.dto.ActivityResponse;
import com.travelplan.travel.dto.DestinationResponse;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Thin Elasticsearch dual-write hook, called from {@link DestinationService}
 * on create/update/soft-delete — see docs/lets-travel-architecture-decisions.md
 * §6.
 *
 * <p>Synchronous, applicative dual-write, no CDC/broker: {@link #indexOrUpdate}
 * is called right after the Neo4j write that made it the source of truth
 * (i.e. with the exact same data {@code DestinationService} is about to
 * return to its caller), {@link #remove} right after a soft-delete. Every
 * failure here is logged and swallowed, NEVER rethrown: Neo4j is the source
 * of truth and must never fail a request because its secondary search index
 * had a hiccup (same philosophy as {@code PaymentServiceClient}'s "logged and
 * swallowed" cross-service calls elsewhere in this codebase). A failure here
 * leaves the index briefly stale — accepted as re-indexing-on-demand debt,
 * not a strong consistency guarantee (§6).</p>
 *
 * <p>Soft-delete is mirrored as a real document removal (not an "inactive"
 * flag) — see §6's correction note for why: unlike Neo4j, an Elasticsearch
 * document is a derived projection, never the source of truth, so deleting
 * it loses nothing and mechanically guarantees a soft-deleted destination
 * can never resurface in a search/autocomplete result.</p>
 */
@Service
public class DestinationSearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(DestinationSearchIndexer.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public DestinationSearchIndexer(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Index (or re-index, same operation in Elasticsearch — a PUT on a
     * known document id upserts) the given destination, including its
     * activities/accommodations — "across all travel details", per the
     * subject's wording for this feature.
     */
    public void indexOrUpdate(DestinationResponse destination) {
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", destination.getId().toString());
            doc.put("name", destination.getName());
            doc.put("country", destination.getCountry());
            doc.put("price", destination.getPrice());
            doc.put("capacity", destination.getCapacity());
            doc.put("startDate", destination.getStartDate() == null ? null : destination.getStartDate().toString());
            doc.put("endDate", destination.getEndDate() == null ? null : destination.getEndDate().toString());
            doc.put("managerId", destination.getManagerId() == null ? null : destination.getManagerId().toString());
            doc.put("activities", destination.getActivities().stream()
                    .map(ActivityResponse::getName)
                    .collect(Collectors.toList()));
            doc.put("accommodations", destination.getAccommodations().stream()
                    .map(AccommodationResponse::getName)
                    .collect(Collectors.toList()));
            doc.put("suggest", Map.of("input", suggestInputs(destination)));

            Request request = new Request("PUT", "/" + ElasticsearchIndexInitializer.INDEX_NAME
                    + "/_doc/" + destination.getId());
            // wait_for: returns only once the next scheduled refresh makes this
            // write visible to search — deterministic without paying the cost
            // of forcing an immediate refresh (refresh=true) on every write.
            request.addParameter("refresh", "wait_for");
            request.setJsonEntity(objectMapper.writeValueAsString(doc));
            restClient.performRequest(request);
        } catch (Exception ex) {
            log.warn("Elasticsearch indexing failed for destination {} — the Neo4j write already "
                    + "succeeded and is unaffected; the search index may be briefly stale until a "
                    + "future re-index.", destination.getId(), ex);
        }
    }

    /**
     * Remove the destination's document from the index — the soft-delete
     * mirror. A missing document (404) is not an error: it can legitimately
     * happen if a prior indexing attempt already failed and was swallowed.
     */
    public void remove(UUID destinationId) {
        try {
            Request request = new Request("DELETE", "/" + ElasticsearchIndexInitializer.INDEX_NAME
                    + "/_doc/" + destinationId);
            request.addParameter("refresh", "wait_for");
            restClient.performRequest(request);
        } catch (ResponseException ex) {
            if (ex.getResponse().getStatusLine().getStatusCode() != 404) {
                log.warn("Elasticsearch removal failed for destination {} — the search index may be "
                        + "briefly stale until a future re-index.", destinationId, ex);
            }
        } catch (Exception ex) {
            log.warn("Elasticsearch removal failed for destination {} — the search index may be "
                    + "briefly stale until a future re-index.", destinationId, ex);
        }
    }

    private static List<String> suggestInputs(DestinationResponse destination) {
        List<String> inputs = new ArrayList<>();
        if (destination.getName() != null && !destination.getName().isBlank()) {
            inputs.add(destination.getName());
        }
        if (destination.getCountry() != null && !destination.getCountry().isBlank()) {
            inputs.add(destination.getCountry());
        }
        return inputs;
    }
}
