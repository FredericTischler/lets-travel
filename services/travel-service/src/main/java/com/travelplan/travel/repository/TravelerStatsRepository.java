package com.travelplan.travel.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only source of the traveler's personal statistics
 * (docs/lets-travel-architecture-decisions.md, "Dashboards" addendum): every
 * {@code SUBSCRIBED} relation of one traveler with the summary of its
 * destination (with dates — {@link SubscriptionRepository}'s traveler view has
 * no end date) and whether the traveler already left a feedback there.
 *
 * Kept apart from {@link SubscriptionRepository} and {@link FeedbackRepository}
 * (which own those relations) for the same reason as {@link ManagerStatsRepository}:
 * this class only reads, and filters {@code d.deletedAt IS NULL} on the destination.
 * The statuses are the <b>stored</b> ones ({@code ACTIVE}, {@code PENDING_PAYMENT},
 * {@code CANCELLED}); the caller decides what counts as participation or as a
 * cancellation.
 */
@Repository
public class TravelerStatsRepository {

    private static final String SUBSCRIPTIONS_QUERY = """
            MATCH (t:TravelerRef {userId: $travelerId})-[s:SUBSCRIBED]->(d:Destination)
            WHERE d.deletedAt IS NULL
            RETURN s.status AS status, d.id AS destinationId, d.name AS name, d.country AS country,
                   d.startDate AS startDate, d.endDate AS endDate,
                   EXISTS { (t)-[:GAVE_FEEDBACK]->(d) } AS feedbackGiven
            ORDER BY d.endDate DESC
            """;

    private final Neo4jClient neo4jClient;

    public TravelerStatsRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /** Every subscription relation (any stored status) of {@code travelerId}, latest end date first. */
    public List<TravelerSubscriptionSummary> findSubscriptions(UUID travelerId) {
        return neo4jClient.query(SUBSCRIPTIONS_QUERY)
                .bindAll(Map.of("travelerId", travelerId.toString()))
                .fetchAs(TravelerSubscriptionSummary.class)
                .mappedBy((typeSystem, row) -> new TravelerSubscriptionSummary(
                        row.get("status").asString(),
                        UUID.fromString(row.get("destinationId").asString()),
                        row.get("name").asString(),
                        row.get("country").asString(),
                        row.get("startDate").isNull() ? null : row.get("startDate").asLocalDate(),
                        row.get("endDate").isNull() ? null : row.get("endDate").asLocalDate(),
                        row.get("feedbackGiven").asBoolean()))
                .all()
                .stream()
                .toList();
    }

    /**
     * One subscription of the traveler with its destination's summary. Not an API type.
     *
     * @param status        stored status of the relation
     * @param feedbackGiven whether the traveler already gave feedback on the destination
     */
    public record TravelerSubscriptionSummary(String status, UUID destinationId, String name, String country,
                                              LocalDate startDate, LocalDate endDate, boolean feedbackGiven) {
    }
}
