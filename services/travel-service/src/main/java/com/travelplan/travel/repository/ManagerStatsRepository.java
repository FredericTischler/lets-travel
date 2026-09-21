package com.travelplan.travel.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
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
 * payment-service (composed on top by {@code DashboardService}/
 * {@code ManagerStatsService}, see the "Dashboards" ADR addendum) and report
 * counts in identity-service (composed by the front).
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

    /**
     * One row per active destination that has a manager (all managers, or just
     * {@code $managerId} when given), with its ACTIVE subscriber count and its
     * feedback aggregate. Two {@code CALL} subqueries instead of two
     * {@code OPTIONAL MATCH}es on the same row, which would multiply the counts.
     */
    private static final String DESTINATION_SUMMARIES_QUERY = """
            MATCH (d:Destination)
            WHERE d.managerId IS NOT NULL AND d.deletedAt IS NULL
              AND ($managerId IS NULL OR d.managerId = $managerId)
            CALL {
              WITH d
              OPTIONAL MATCH (:TravelerRef)-[s:SUBSCRIBED {status: 'ACTIVE'}]->(d)
              RETURN count(s) AS subscribers
            }
            CALL {
              WITH d
              OPTIONAL MATCH (:TravelerRef)-[f:GAVE_FEEDBACK]->(d)
              RETURN count(f) AS feedbackCount, coalesce(sum(f.rating), 0) AS ratingSum
            }
            RETURN d.id AS destinationId, d.managerId AS managerId, d.name AS name, d.country AS country,
                   d.startDate AS startDate, d.endDate AS endDate, d.capacity AS capacity,
                   subscribers, feedbackCount, ratingSum
            ORDER BY startDate DESC
            """;

    /** Distinct ACTIVE travelers per manager (a traveler on two of a manager's travels counts once). */
    private static final String SUBSCRIBERS_BY_MANAGER_QUERY = """
            MATCH (t:TravelerRef)-[:SUBSCRIBED {status: 'ACTIVE'}]->(d:Destination)
            WHERE d.managerId IS NOT NULL AND d.deletedAt IS NULL
            RETURN d.managerId AS managerId, count(DISTINCT t) AS subscribers
            """;

    /** Distinct ACTIVE travelers over every active destination, all managers together. */
    private static final String ALL_SUBSCRIBERS_QUERY = """
            MATCH (t:TravelerRef)-[:SUBSCRIBED {status: 'ACTIVE'}]->(d:Destination)
            WHERE d.deletedAt IS NULL
            RETURN count(DISTINCT t) AS subscribers
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

    /**
     * Every active destination with a manager (or only {@code managerId}'s,
     * when not {@code null}), newest start first, each with its ACTIVE
     * subscriber count and feedback aggregate — the one read behind the
     * ranking, the manager dashboard and the admin dashboard.
     */
    public List<DestinationSummaryView> findDestinationSummaries(UUID managerId) {
        Map<String, Object> params = new HashMap<>();
        params.put("managerId", managerId == null ? null : managerId.toString());
        return neo4jClient.query(DESTINATION_SUMMARIES_QUERY)
                .bindAll(params)
                .fetchAs(DestinationSummaryView.class)
                .mappedBy((typeSystem, record) -> new DestinationSummaryView(
                        UUID.fromString(record.get("destinationId").asString()),
                        UUID.fromString(record.get("managerId").asString()),
                        record.get("name").asString(),
                        record.get("country").asString(),
                        record.get("startDate").isNull() ? null : record.get("startDate").asLocalDate(),
                        record.get("endDate").isNull() ? null : record.get("endDate").asLocalDate(),
                        record.get("capacity").isNull() ? null : record.get("capacity").asInt(),
                        record.get("subscribers").asLong(),
                        record.get("feedbackCount").asLong(),
                        record.get("ratingSum").asLong()))
                .all()
                .stream()
                .toList();
    }

    /** Distinct ACTIVE travelers per manager owning at least one active destination. */
    public Map<UUID, Long> findActiveSubscribersByManager() {
        Map<UUID, Long> result = new HashMap<>();
        neo4jClient.query(SUBSCRIBERS_BY_MANAGER_QUERY)
                .fetch()
                .all()
                .forEach(row -> result.put(UUID.fromString((String) row.get("managerId")),
                        ((Number) row.get("subscribers")).longValue()));
        return result;
    }

    /** Distinct travelers with an ACTIVE subscription on any active destination (platform-wide). */
    public long countAllActiveSubscribers() {
        return neo4jClient.query(ALL_SUBSCRIBERS_QUERY)
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("subscribers").asLong())
                .one()
                .orElse(0L);
    }
}
