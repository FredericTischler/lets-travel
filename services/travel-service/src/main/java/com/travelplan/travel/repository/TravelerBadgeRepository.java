package com.travelplan.travel.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only aggregation behind traveler badges (bonus feature,
 * docs/lets-travel-architecture-decisions.md §12): how many distinct
 * countries/destinations a traveler has actually visited (an {@code ACTIVE}
 * subscription whose destination has ended — same "participated" rule as
 * {@code FeedbackService}, §5ter.1), and how many reviews they have given.
 *
 * Kept apart from {@link SubscriptionRepository}/{@link FeedbackRepository}
 * (which own those relations) for the same reason {@link ManagerStatsRepository}
 * is separate: this class only reads, across both relations, and never writes.
 */
@Repository
public class TravelerBadgeRepository {

    private static final String COUNTS_QUERY = """
            OPTIONAL MATCH (t:TravelerRef {userId: $travelerId})
            OPTIONAL MATCH (t)-[s:SUBSCRIBED {status: 'ACTIVE'}]->(d:Destination)
              WHERE d.deletedAt IS NULL AND d.endDate < $today
            WITH t, count(DISTINCT d.id) AS destinationsVisited, count(DISTINCT d.country) AS countriesVisited
            OPTIONAL MATCH (t)-[f:GAVE_FEEDBACK]->(fd:Destination) WHERE fd.deletedAt IS NULL
            RETURN destinationsVisited, countriesVisited, count(f) AS reviewsGiven
            """;

    private final Neo4jClient neo4jClient;

    public TravelerBadgeRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * {@code destinationsVisited}/{@code countriesVisited} count only
     * {@code ACTIVE} subscriptions on destinations that have already ended;
     * {@code reviewsGiven} counts every {@code GAVE_FEEDBACK} regardless of
     * end date (a review can only exist once the trip is over anyway,
     * §5ter.1). All three are 0, never absent, for a traveler with no
     * history at all.
     */
    public Counts findCounts(UUID travelerId, LocalDate today) {
        return neo4jClient.query(COUNTS_QUERY)
                .bindAll(Map.of("travelerId", travelerId.toString(), "today", today))
                .fetchAs(Counts.class)
                .mappedBy((typeSystem, row) -> new Counts(
                        row.get("destinationsVisited").asInt(),
                        row.get("countriesVisited").asInt(),
                        row.get("reviewsGiven").asInt()))
                .one()
                .orElse(new Counts(0, 0, 0));
    }

    public record Counts(int destinationsVisited, int countriesVisited, int reviewsGiven) {
    }
}
