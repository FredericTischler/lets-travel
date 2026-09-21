package com.travelplan.travel.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only aggregations over {@code Destination}, {@code SUBSCRIBED} and
 * {@code GAVE_FEEDBACK} for the manager statistics/ranking endpoints
 * (docs/lets-travel-architecture-decisions.md §5 addendum).
 *
 * Kept apart from {@link FeedbackRepository} (which owns the feedback
 * relation itself) and {@link SubscriptionRepository} (owned by the
 * subscription/payment work): this class only reads, across both relations,
 * and never writes. Every query filters {@code d.deletedAt IS NULL} on the
 * destination, so a soft-deleted travel drops out of a manager's statistics
 * and out of the ranking altogether.
 *
 * Only what travel-service owns is aggregated here — income lives in
 * payment-service and report counts in identity-service; combining those into
 * a final performance score is deliberately left to a later phase / the
 * dashboard.
 */
@Repository
public class ManagerStatsRepository {

    /**
     * One row per active destination of the manager. The {@code OPTIONAL
     * MATCH} keeps destinations with no feedback (count 0); the grouping keys
     * are the destination's own properties, so each destination is exactly
     * one row.
     */
    private static final String DESTINATION_RATINGS_QUERY = """
            MATCH (d:Destination) WHERE d.managerId = $managerId AND d.deletedAt IS NULL
            OPTIONAL MATCH (:TravelerRef)-[f:GAVE_FEEDBACK]->(d)
            RETURN d.id AS destinationId, d.name AS name, d.country AS country,
                   d.startDate AS startDate, d.endDate AS endDate,
                   count(f) AS feedbackCount, coalesce(sum(f.rating), 0) AS ratingSum
            ORDER BY startDate DESC
            """;

    /** Distinct travelers holding an ACTIVE subscription on any active destination of the manager. */
    private static final String SUBSCRIBERS_QUERY = """
            MATCH (t:TravelerRef)-[:SUBSCRIBED {status: 'ACTIVE'}]->(d:Destination)
            WHERE d.managerId = $managerId AND d.deletedAt IS NULL
            RETURN count(DISTINCT t) AS subscribers
            """;

    private static final String ALL_MANAGERS_QUERY = """
            MATCH (d:Destination) WHERE d.managerId IS NOT NULL AND d.deletedAt IS NULL
            OPTIONAL MATCH (:TravelerRef)-[f:GAVE_FEEDBACK]->(d)
            RETURN d.managerId AS managerId, count(DISTINCT d) AS activeTravels,
                   count(f) AS feedbackCount, coalesce(sum(f.rating), 0) AS ratingSum
            """;

    private final Neo4jClient neo4jClient;

    public ManagerStatsRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /** Every active destination of {@code managerId} with its feedback aggregate. */
    public List<ManagerDestinationRatingView> findDestinationRatings(UUID managerId) {
        return neo4jClient.query(DESTINATION_RATINGS_QUERY)
                .bindAll(Map.of("managerId", managerId.toString()))
                .fetchAs(ManagerDestinationRatingView.class)
                .mappedBy((typeSystem, record) -> new ManagerDestinationRatingView(
                        UUID.fromString(record.get("destinationId").asString()),
                        record.get("name").asString(),
                        record.get("country").asString(),
                        record.get("startDate").isNull() ? null : record.get("startDate").asLocalDate(),
                        record.get("endDate").isNull() ? null : record.get("endDate").asLocalDate(),
                        record.get("feedbackCount").asLong(),
                        record.get("ratingSum").asLong()))
                .all()
                .stream()
                .toList();
    }

    /** Number of distinct travelers currently (ACTIVE) subscribed to any active destination of the manager. */
    public long countActiveSubscribers(UUID managerId) {
        return neo4jClient.query(SUBSCRIBERS_QUERY)
                .bindAll(Map.of("managerId", managerId.toString()))
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("subscribers").asLong())
                .one()
                .orElse(0L);
    }

    /** One aggregate per manager owning at least one active destination. Unordered. */
    public List<ManagerRatingView> findAllManagerAggregates() {
        return neo4jClient.query(ALL_MANAGERS_QUERY)
                .fetchAs(ManagerRatingView.class)
                .mappedBy((typeSystem, record) -> new ManagerRatingView(
                        UUID.fromString(record.get("managerId").asString()),
                        record.get("activeTravels").asLong(),
                        record.get("feedbackCount").asLong(),
                        record.get("ratingSum").asLong()))
                .all()
                .stream()
                .toList();
    }
}
