package com.travelplan.travel.repository;

import org.neo4j.driver.Record;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data access for the {@code SUBSCRIBED} relationship
 * (docs/lets-travel-architecture-decisions.md §3): {@code
 * (TravelerRef {userId})-[:SUBSCRIBED {status, subscribedAt,
 * cancelledAt}]->(Destination)}.
 *
 * {@code TravelerRef} is a lightweight applicative reference, never a copy
 * of identity-service's {@code User} — it is created on demand via
 * {@code MERGE} on {@code userId} the first time a given traveler subscribes
 * to anything, exactly the "applicative reference, no FK" principle already
 * used for {@code Payment.userId} and {@code Destination.managerId}.
 *
 * Explicit Cypher via {@link Neo4jClient}, not an SDN {@code @Relationship}
 * on a loaded aggregate — same reasoning as {@link TransportRepository}/
 * {@link ActivityRepository}: a load-modify-save flow on {@code Destination}
 * would delete and recreate every relation of a type it did not fully load,
 * which would silently wipe other travelers' subscriptions.
 *
 * A new relation is created on every {@link #subscribe}, never a reused/
 * updated one: cancelling then re-subscribing produces a second, distinct
 * {@code SUBSCRIBED} relation rather than resetting the first one's
 * timestamps. This is deliberate — the traveler's personal stats page needs
 * an accurate historical count of cancellations (sujet: "subscription
 * cancellations"), which a single reused relation per pair would lose.
 *
 * No business logic lives here — existence/soft-delete/ownership checks on
 * the destination, the duplicate-active-subscription check, and the 3-day
 * cutoff are all the caller's ({@code SubscriptionService}'s)
 * responsibility; this class only reads/writes the relation itself. Every
 * read still filters {@code d.deletedAt IS NULL} in its own Cypher as a
 * second line of defense, consistent with the rest of the codebase.
 */
@Repository
public class SubscriptionRepository {

    private static final String HAS_ACTIVE_QUERY = """
            MATCH (t:TravelerRef {userId: $travelerId})-[s:SUBSCRIBED {status: 'ACTIVE'}]->(d:Destination)
            WHERE d.id = $destinationId AND d.deletedAt IS NULL
            RETURN count(s) AS activeCount
            """;

    private static final String SUBSCRIBE_QUERY = """
            MATCH (d:Destination) WHERE d.id = $destinationId AND d.deletedAt IS NULL
            MERGE (t:TravelerRef {userId: $travelerId})
            CREATE (t)-[s:SUBSCRIBED {status: 'ACTIVE', subscribedAt: $subscribedAt, cancelledAt: null}]->(d)
            RETURN d.id AS destinationId, t.userId AS travelerId, s.status AS status,
                   s.subscribedAt AS subscribedAt, s.cancelledAt AS cancelledAt
            """;

    private static final String CANCEL_ACTIVE_QUERY = """
            MATCH (t:TravelerRef {userId: $travelerId})-[s:SUBSCRIBED {status: 'ACTIVE'}]->(d:Destination)
            WHERE d.id = $destinationId AND d.deletedAt IS NULL
            SET s.status = 'CANCELLED', s.cancelledAt = $cancelledAt
            RETURN count(s) AS updated
            """;

    private static final String FIND_FOR_DESTINATION_QUERY = """
            MATCH (t:TravelerRef)-[s:SUBSCRIBED]->(d:Destination)
            WHERE d.id = $destinationId AND d.deletedAt IS NULL
            RETURN d.id AS destinationId, t.userId AS travelerId, s.status AS status,
                   s.subscribedAt AS subscribedAt, s.cancelledAt AS cancelledAt
            ORDER BY s.subscribedAt DESC
            """;

    private static final String FIND_FOR_TRAVELER_QUERY = """
            MATCH (t:TravelerRef {userId: $travelerId})-[s:SUBSCRIBED]->(d:Destination)
            WHERE d.deletedAt IS NULL
            RETURN d.id AS destinationId, d.name AS destinationName, d.country AS destinationCountry,
                   d.startDate AS destinationStartDate, s.status AS status,
                   s.subscribedAt AS subscribedAt, s.cancelledAt AS cancelledAt
            ORDER BY s.subscribedAt DESC
            """;

    private final Neo4jClient neo4jClient;

    public SubscriptionRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Whether {@code travelerId} already holds an {@code ACTIVE} subscription
     * for {@code destinationId}.
     */
    public boolean hasActiveSubscription(UUID travelerId, UUID destinationId) {
        Long count = neo4jClient.query(HAS_ACTIVE_QUERY)
                .bindAll(idParams(travelerId, destinationId))
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("activeCount").asLong())
                .one()
                .orElse(0L);
        return count > 0;
    }

    /**
     * Create a new {@code ACTIVE} {@code SUBSCRIBED} relation from
     * {@code travelerId} (merged into a {@code TravelerRef} node) to
     * {@code destinationId}. Assumes the destination already exists, is
     * active, and that the duplicate-active check has already passed
     * (checked by the caller).
     */
    public SubscriptionView subscribe(UUID travelerId, UUID destinationId, OffsetDateTime subscribedAt) {
        Map<String, Object> params = idParams(travelerId, destinationId);
        params.put("subscribedAt", subscribedAt);
        return neo4jClient.query(SUBSCRIBE_QUERY)
                .bindAll(params)
                .fetchAs(SubscriptionView.class)
                .mappedBy((typeSystem, record) -> toSubscriptionView(record))
                .one()
                .orElseThrow(() -> new IllegalStateException(
                        "Subscribe query returned no row for destination " + destinationId));
    }

    /**
     * Cancel the caller's {@code ACTIVE} subscription for
     * {@code destinationId}, if any. Returns {@code false} (no-op) when no
     * matching active relation exists, leaving the "not found" decision to
     * the caller.
     */
    public boolean cancelActive(UUID travelerId, UUID destinationId, OffsetDateTime cancelledAt) {
        Map<String, Object> params = idParams(travelerId, destinationId);
        params.put("cancelledAt", cancelledAt);
        long updated = neo4jClient.query(CANCEL_ACTIVE_QUERY)
                .bindAll(params)
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("updated").asLong())
                .one()
                .orElse(0L);
        return updated > 0;
    }

    /**
     * All subscription rows (any status) for a single active destination —
     * the manager/admin-facing subscriber list.
     */
    public List<SubscriptionView> findForDestination(UUID destinationId) {
        return neo4jClient.query(FIND_FOR_DESTINATION_QUERY)
                .bindAll(Map.of("destinationId", destinationId.toString()))
                .fetchAs(SubscriptionView.class)
                .mappedBy((typeSystem, record) -> toSubscriptionView(record))
                .all()
                .stream()
                .toList();
    }

    /**
     * All subscription rows (any status) for a single traveler, across every
     * active destination — the traveler's own subscription history.
     */
    public List<TravelerSubscriptionView> findForTraveler(UUID travelerId) {
        return neo4jClient.query(FIND_FOR_TRAVELER_QUERY)
                .bindAll(Map.of("travelerId", travelerId.toString()))
                .fetchAs(TravelerSubscriptionView.class)
                .mappedBy((typeSystem, record) -> new TravelerSubscriptionView(
                        UUID.fromString(record.get("destinationId").asString()),
                        record.get("destinationName").asString(),
                        record.get("destinationCountry").asString(),
                        record.get("destinationStartDate").isNull()
                                ? null : record.get("destinationStartDate").asLocalDate(),
                        record.get("status").asString(),
                        record.get("subscribedAt").isNull()
                                ? null : record.get("subscribedAt").asOffsetDateTime(),
                        record.get("cancelledAt").isNull()
                                ? null : record.get("cancelledAt").asOffsetDateTime()))
                .all()
                .stream()
                .toList();
    }

    private static SubscriptionView toSubscriptionView(Record record) {
        return new SubscriptionView(
                UUID.fromString(record.get("destinationId").asString()),
                UUID.fromString(record.get("travelerId").asString()),
                record.get("status").asString(),
                record.get("subscribedAt").isNull() ? null : record.get("subscribedAt").asOffsetDateTime(),
                record.get("cancelledAt").isNull() ? null : record.get("cancelledAt").asOffsetDateTime());
    }

    private static Map<String, Object> idParams(UUID travelerId, UUID destinationId) {
        Map<String, Object> params = new HashMap<>();
        params.put("travelerId", travelerId.toString());
        params.put("destinationId", destinationId.toString());
        return params;
    }
}
