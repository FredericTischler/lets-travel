package com.travelplan.travel.repository;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The graph traversal behind {@code GET /travelers/me/recommendations}
 * (docs/lets-travel-architecture-decisions.md §7): content-based matching
 * between the destinations a traveler could still join and the ones in their
 * history.
 *
 * Explicit Cypher through {@link Neo4jClient}, read-only, like
 * {@link ManagerStatsRepository}. No weight and no scoring lives here — this
 * query only establishes <i>facts</i> (same country? which activities in
 * common? …) and {@link com.travelplan.travel.service.RecommendationScorer}
 * turns them into a score. The only tunable that crosses the boundary is the
 * price tolerance, because "is this price close?" needs the two prices side by
 * side and is cheaper to answer where the data already is.
 *
 * <p><b>Soft-delete.</b> {@code deletedAt IS NULL} is filtered on every node
 * the traversal touches: the candidate, the history destination, and the
 * {@code Activity}/{@code Accommodation} nodes hanging off both (those two
 * have no {@code deletedAt} today, so the filter is a no-op that keeps this
 * query correct if they ever get one). {@code TravelerRef} and the
 * {@code SUBSCRIBED}/{@code GAVE_FEEDBACK} relations carry no soft-delete
 * flag; a relation to a soft-deleted destination is ignored through that
 * destination.</p>
 */
@Repository
public class RecommendationRepository {

    /**
     * Paragraph by paragraph:
     *
     * <ol>
     *   <li><b>Eligible candidates</b> — active, {@code startDate >= today}
     *       (the same rule as subscribing, so we never suggest a trip the API
     *       would refuse with a 409), not already live for this traveler
     *       ({@code ACTIVE}, or a {@code PENDING_PAYMENT} still inside its
     *       hold: subscribing again would be a 409 too; a {@code CANCELLED}
     *       or expired one does not block), and not full (live seats <
     *       {@code capacity}, counted like {@code SubscriptionRepository}).</li>
     *   <li><b>Candidate attributes</b> — activity names and accommodation
     *       types, collected once per candidate.</li>
     *   <li><b>Traveler history</b> — every destination the traveler took part
     *       in ({@code ACTIVE} subscription) or rated ({@code GAVE_FEEDBACK}),
     *       folded into one row per destination: {@code participated} and
     *       {@code rating}. A {@code PENDING_PAYMENT}/{@code CANCELLED}
     *       subscription alone is not history. The {@code OPTIONAL MATCH}
     *       keeps a candidate alive with {@code h = null} when there is no
     *       history at all (cold start).</li>
     *   <li><b>Pair facts</b> — same country, shared activities, shared
     *       accommodation types, similar price (within {@code $priceTolerance}
     *       of the history destination's price; two free trips also match).
     *       Comparisons are case- and whitespace-insensitive.</li>
     * </ol>
     */
    private static final String CANDIDATE_ROWS_QUERY = """
            MATCH (c:Destination)
            WHERE c.deletedAt IS NULL AND c.startDate >= $today
              AND NOT EXISTS {
                MATCH (:TravelerRef {userId: $travelerId})-[mine:SUBSCRIBED]->(c)
                WHERE mine.status = 'ACTIVE' OR (mine.status = 'PENDING_PAYMENT' AND mine.expiresAt > $now)
              }
            OPTIONAL MATCH (:TravelerRef)-[seat:SUBSCRIBED]->(c)
              WHERE seat.status = 'ACTIVE' OR (seat.status = 'PENDING_PAYMENT' AND seat.expiresAt > $now)
            WITH c, count(seat) AS seatsTaken,
                 count(CASE WHEN seat.status = 'ACTIVE' THEN 1 END) AS activeSubscribers
            WHERE c.capacity IS NULL OR seatsTaken < c.capacity

            OPTIONAL MATCH (c)-[:HAS_ACTIVITY]->(ca:Activity) WHERE ca.deletedAt IS NULL
            WITH c, activeSubscribers, collect(DISTINCT ca.name) AS cActivities
            OPTIONAL MATCH (c)-[:HAS_ACCOMMODATION]->(cb:Accommodation) WHERE cb.deletedAt IS NULL
            WITH c, activeSubscribers, cActivities, collect(DISTINCT cb.type) AS cTypes

            OPTIONAL MATCH (:TravelerRef {userId: $travelerId})-[r:SUBSCRIBED|GAVE_FEEDBACK]->(h:Destination)
              WHERE h.deletedAt IS NULL AND h <> c
                AND (type(r) = 'GAVE_FEEDBACK' OR r.status = 'ACTIVE')
            WITH c, activeSubscribers, cActivities, cTypes, h,
                 count(CASE WHEN type(r) = 'SUBSCRIBED' THEN 1 END) > 0 AS participated,
                 max(CASE WHEN type(r) = 'GAVE_FEEDBACK' THEN r.rating END) AS rating
            OPTIONAL MATCH (h)-[:HAS_ACTIVITY]->(ha:Activity) WHERE ha.deletedAt IS NULL
            WITH c, activeSubscribers, cActivities, cTypes, h, participated, rating,
                 collect(DISTINCT toLower(trim(ha.name))) AS hActivities
            OPTIONAL MATCH (h)-[:HAS_ACCOMMODATION]->(hb:Accommodation) WHERE hb.deletedAt IS NULL
            WITH c, activeSubscribers, cActivities, cTypes, h, participated, rating, hActivities,
                 collect(DISTINCT toLower(trim(hb.type))) AS hTypes

            RETURN c.id AS id, c.name AS name, c.country AS country,
                   c.startDate AS startDate, c.endDate AS endDate, c.price AS price,
                   activeSubscribers,
                   h.id AS hId, h.name AS hName, h.country AS hCountry, h.price AS hPrice,
                   participated, rating,
                   coalesce(toLower(trim(c.country)) = toLower(trim(h.country)), false) AS sameCountry,
                   [a IN cActivities WHERE toLower(trim(a)) IN hActivities] AS sharedActivities,
                   [t IN cTypes WHERE toLower(trim(t)) IN hTypes] AS sharedTypes,
                   CASE
                     WHEN h IS NULL THEN false
                     WHEN coalesce(toFloat(h.price), 0.0) = 0.0 THEN coalesce(toFloat(c.price), 0.0) = 0.0
                     ELSE abs(coalesce(toFloat(c.price), 0.0) - toFloat(h.price))
                          <= $priceTolerance * toFloat(h.price)
                   END AS similarPrice
            """;

    private final Neo4jClient neo4jClient;

    public RecommendationRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Every eligible candidate for {@code travelerId}, one row per
     * (candidate, history destination) pair — or a single row with a
     * {@code null} history when the traveler has none. Unordered: ranking is
     * the scorer's job.
     */
    public List<RecommendationRow> findCandidateRows(UUID travelerId, LocalDate today, OffsetDateTime now,
                                                     double priceTolerance) {
        Map<String, Object> params = new HashMap<>();
        params.put("travelerId", travelerId.toString());
        params.put("today", today);
        params.put("now", now);
        params.put("priceTolerance", priceTolerance);
        return neo4jClient.query(CANDIDATE_ROWS_QUERY)
                .bindAll(params)
                .fetchAs(RecommendationRow.class)
                .mappedBy((typeSystem, record) -> toRow(record))
                .all()
                .stream()
                .toList();
    }

    private static RecommendationRow toRow(Record record) {
        RecommendationRow.HistoryMatch history = record.get("hId").isNull() ? null
                : new RecommendationRow.HistoryMatch(
                        UUID.fromString(record.get("hId").asString()),
                        record.get("hName").asString(),
                        record.get("hCountry").isNull() ? null : record.get("hCountry").asString(),
                        price(record.get("hPrice")),
                        record.get("rating").isNull() ? null : record.get("rating").asInt(),
                        record.get("participated").asBoolean(),
                        record.get("sameCountry").asBoolean(),
                        record.get("sharedActivities").asList(Value::asString),
                        record.get("sharedTypes").asList(Value::asString),
                        record.get("similarPrice").asBoolean());
        return new RecommendationRow(
                UUID.fromString(record.get("id").asString()),
                record.get("name").asString(),
                record.get("country").isNull() ? null : record.get("country").asString(),
                record.get("startDate").isNull() ? null : record.get("startDate").asLocalDate(),
                record.get("endDate").isNull() ? null : record.get("endDate").asLocalDate(),
                price(record.get("price")),
                record.get("activeSubscribers").asLong(),
                history);
    }

    /** Spring Data Neo4j stores a {@code BigDecimal} as a string; tolerate a native number too. */
    private static BigDecimal price(Value value) {
        return value.isNull() ? null : new BigDecimal(value.asObject().toString());
    }
}
