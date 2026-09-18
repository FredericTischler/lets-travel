package com.travelplan.travel.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.neo4j.core.Neo4jClient;

/**
 * Ensures the {@code Destination.id} uniqueness constraint exists at startup.
 *
 * No Flyway / neo4j-migrations tool is used here: for a single node type with
 * a single, simple constraint, an idempotent Cypher statement run once at
 * boot is simpler than introducing a dedicated migration tool and its own
 * bookkeeping (schema history node, checksum tracking, etc.). Reconsider this
 * choice if the graph model grows (multiple node types, relationships,
 * ordered/versioned schema changes).
 *
 * {@code CREATE CONSTRAINT ... IF NOT EXISTS} is idempotent: safe to run on
 * every startup, including against an already-initialised database.
 *
 * Same constraint is applied to {@code Activity.id} and
 * {@code Accommodation.id}: both are application-assigned UUIDs (see their
 * respective entity Javadoc), so uniqueness is not guaranteed by Neo4j
 * itself unless declared.
 *
 * {@code TravelerRef.userId} gets the same treatment (docs/lets-travel-architecture-decisions.md
 * §3): {@code SubscriptionRepository} creates it via {@code MERGE}, and a
 * uniqueness constraint is what makes that {@code MERGE} race-safe under
 * concurrent first-time subscribes for the same traveler — without it,
 * two concurrent transactions could each decide "no match" and both
 * {@code CREATE} a distinct {@code TravelerRef} node for the same
 * {@code userId}. This is a plain single-property uniqueness constraint
 * (supported by Neo4j Community, unlike node-key/composite constraints),
 * not to be confused with the traveler×destination pairing uniqueness the
 * ADR explicitly leaves unenforced at the database level.
 *
 * <p>{@code IF NOT EXISTS} is idempotent at the level of the final schema
 * state, but not at the level of a single execution: when two instances of
 * this application start concurrently against the same, not-yet-initialised
 * database (e.g. two Kubernetes replicas or two Docker Compose replicas
 * coming up together), both may try to create the same constraint at the
 * same time. Neo4j's Forseti locking can then detect a deadlock between the
 * two schema transactions and abort one of them with a
 * {@code Neo.TransientError.Transaction.DeadlockDetected}, surfaced here as
 * a {@link TransientDataAccessException} (the Spring Data Neo4j translation
 * of the driver's {@code TransientException}). That failure is transient by
 * definition: retrying the same statement shortly after succeeds once the
 * other instance's transaction has committed. Each constraint statement is
 * therefore retried a bounded number of times with a short fixed backoff.
 * Any other exception (invalid Cypher, an actually unreachable database,
 * etc.) is not transient and is left to fail startup immediately, as
 * before.</p>
 */
@Configuration
public class Neo4jSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(Neo4jSchemaInitializer.class);

    /** Maximum attempts per constraint statement, including the first one. */
    private static final int MAX_ATTEMPTS = 5;

    /** Fixed delay between retries. Deadlocks resolve fast; no need for exponential growth here. */
    private static final long RETRY_DELAY_MILLIS = 500L;

    @Bean
    CommandLineRunner ensureDestinationIdUniqueConstraint(Neo4jClient neo4jClient) {
        return args -> {
            runWithRetry(neo4jClient,
                    "CREATE CONSTRAINT destination_id_unique IF NOT EXISTS "
                            + "FOR (d:Destination) REQUIRE d.id IS UNIQUE");
            runWithRetry(neo4jClient,
                    "CREATE CONSTRAINT activity_id_unique IF NOT EXISTS "
                            + "FOR (a:Activity) REQUIRE a.id IS UNIQUE");
            runWithRetry(neo4jClient,
                    "CREATE CONSTRAINT accommodation_id_unique IF NOT EXISTS "
                            + "FOR (a:Accommodation) REQUIRE a.id IS UNIQUE");
            runWithRetry(neo4jClient,
                    "CREATE CONSTRAINT travelerref_userid_unique IF NOT EXISTS "
                            + "FOR (t:TravelerRef) REQUIRE t.userId IS UNIQUE");
            log.info("Neo4j schema constraints ensured: Destination.id, Activity.id, Accommodation.id, "
                    + "TravelerRef.userId IS UNIQUE");
        };
    }

    /**
     * Runs {@code cypher} via {@code neo4jClient}, retrying on
     * {@link TransientDataAccessException} up to {@link #MAX_ATTEMPTS} times
     * with a fixed delay between attempts. Re-throws the last transient
     * failure once attempts are exhausted, and any non-transient exception
     * immediately without retrying.
     */
    private static void runWithRetry(Neo4jClient neo4jClient, String cypher) {
        int attempt = 1;
        while (true) {
            try {
                neo4jClient.query(cypher).run();
                return;
            } catch (TransientDataAccessException ex) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.error("Neo4j schema statement still failing with a transient error after {} attempts, "
                            + "giving up: {}", attempt, cypher, ex);
                    throw ex;
                }
                log.warn("Transient Neo4j error on attempt {}/{} for schema statement, retrying in {} ms: {}",
                        attempt, MAX_ATTEMPTS, RETRY_DELAY_MILLIS, cypher, ex);
                sleep(RETRY_DELAY_MILLIS);
                attempt++;
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying Neo4j schema initialization", interrupted);
        }
    }
}