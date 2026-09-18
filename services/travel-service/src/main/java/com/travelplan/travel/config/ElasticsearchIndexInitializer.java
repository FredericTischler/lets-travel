package com.travelplan.travel.config;

import jakarta.annotation.PostConstruct;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Creates the {@code destinations} Elasticsearch index (with its mapping) at
 * startup if it does not already exist — same spirit as
 * {@link Neo4jSchemaInitializer}, but for the search index rather than a
 * graph constraint. No schema/mapping provisioning is delegated to
 * Ansible/infra (see ansible/roles/elasticsearch): that role only renders the
 * Compose fragment, this class owns the index shape, per
 * docs/lets-travel-architecture-decisions.md §6.
 *
 * <p>Deliberately tolerant of a missing/unreachable Elasticsearch at
 * startup (see {@link ElasticsearchConnectionConfig}): every failure here is
 * logged and swallowed, never rethrown, so travel-service still boots when
 * the {@code search} Compose profile is not up (or ES is briefly not ready
 * yet) — search/autocomplete just degrade until it is reachable.</p>
 *
 * <p><b>Check-and-create is a single atomic {@code PUT}, not a separate
 * {@code HEAD}-then-{@code PUT}.</b> An earlier version issued a {@code HEAD}
 * existence check first and only created the index on a 404. In practice
 * that races Elasticsearch's own startup: right after the "node started" log
 * line (what Testcontainers' wait strategy — and a Compose healthcheck on
 * {@code /_cluster/health} — both key off), the master service is still
 * draining a queue of startup tasks (ILM policy/template registration), and a
 * {@code HEAD} can report 200 for an index whose creation hasn't actually
 * been applied to cluster state yet, while a near-simultaneous {@code
 * GET .../_mapping} on the same "existing" index 404s with {@code
 * index_not_found_exception} — observed directly while diagnosing this
 * class. Once the index is dynamically auto-created by that inconsistency
 * (or by any other split-brained path), its {@code suggest} field never gets
 * the explicit {@code completion} type, and every autocomplete request 503s
 * forever after (ES rejects a completion-suggester query against a
 * non-completion field). A single {@code PUT} with the mapping is both
 * idempotent (Elasticsearch answers {@code 400
 * resource_already_exists_exception} if it's already there) and atomic — no
 * window for another actor to observe or create a differently-shaped index
 * in between.</p>
 *
 * <p>Bounded retry (see {@link #MAX_ATTEMPTS}) additionally covers the
 * ordinary "Elasticsearch isn't listening yet" case (connection refused),
 * which a single attempt would otherwise treat as a permanent "search
 * profile not up" and never retry even though {@code search} is very much
 * coming up — relevant both to the Testcontainers race above and to the
 * production case where the Compose healthcheck on {@code
 * elasticsearch} (see ansible/roles/elasticsearch) reports healthy a moment
 * before the node is actually ready to accept index-management calls.</p>
 */
@Configuration
public class ElasticsearchIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIndexInitializer.class);

    /** Name of the Elasticsearch index backing search/autocomplete. */
    public static final String INDEX_NAME = "destinations";

    /** Bounded retry for "not reachable/ready yet" — see class Javadoc. */
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 2000;

    private static final String MAPPING_JSON = """
            {
              "mappings": {
                "properties": {
                  "id": { "type": "keyword" },
                  "name": { "type": "text" },
                  "country": { "type": "text" },
                  "activities": { "type": "text" },
                  "accommodations": { "type": "text" },
                  "price": { "type": "double" },
                  "capacity": { "type": "integer" },
                  "startDate": { "type": "date" },
                  "endDate": { "type": "date" },
                  "managerId": { "type": "keyword" },
                  "suggest": { "type": "completion" }
                }
              }
            }
            """;

    private final RestClient restClient;

    public ElasticsearchIndexInitializer(RestClient restClient) {
        this.restClient = restClient;
    }

    @PostConstruct
    void ensureIndexExists() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                createIndexIfAbsent();
                return;
            } catch (ResponseException ex) {
                // A genuine Elasticsearch-level error (not "not reachable yet") —
                // retrying won't help, e.g. a malformed mapping. Log and defer.
                log.warn("Could not create Elasticsearch index '{}' — search/autocomplete may be "
                        + "degraded until Elasticsearch is reachable.", INDEX_NAME, ex);
                return;
            } catch (IOException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Elasticsearch unreachable after {} attempts (profile 'search' not up?) — "
                            + "search/autocomplete will be degraded until it is. Index creation deferred.",
                            MAX_ATTEMPTS, ex);
                    return;
                }
                log.debug("Elasticsearch not reachable yet (attempt {}/{}) — retrying in {} ms.",
                        attempt, MAX_ATTEMPTS, RETRY_DELAY_MS, ex);
                sleepBeforeRetry();
            }
        }
    }

    /**
     * Single atomic check-and-create: {@code PUT} the index with its mapping,
     * treating Elasticsearch's own "already exists" error as success rather
     * than doing a separate existence check first — see class Javadoc for why
     * a split {@code HEAD}-then-{@code PUT} is unsafe here.
     */
    private void createIndexIfAbsent() throws IOException {
        Request request = new Request("PUT", "/" + INDEX_NAME);
        request.setJsonEntity(MAPPING_JSON);
        try {
            restClient.performRequest(request);
            log.info("Created Elasticsearch index '{}'.", INDEX_NAME);
        } catch (ResponseException ex) {
            if (isResourceAlreadyExists(ex)) {
                log.info("Elasticsearch index '{}' already exists.", INDEX_NAME);
            } else {
                throw ex;
            }
        }
    }

    private static boolean isResourceAlreadyExists(ResponseException ex) {
        return ex.getResponse().getStatusLine().getStatusCode() == 400
                && ex.getMessage() != null
                && ex.getMessage().contains("resource_already_exists_exception");
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
