package com.travelplan.travel.repository;

import org.neo4j.driver.Record;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for the {@code GAVE_FEEDBACK} relationship
 * (docs/lets-travel-architecture-decisions.md §5): {@code
 * (TravelerRef {userId})-[:GAVE_FEEDBACK {id, rating, comment,
 * createdAt}]->(Destination)}.
 *
 * Same approach as {@link SubscriptionRepository} and for the same reasons:
 * explicit Cypher through {@link Neo4jClient} (an SDN {@code @Relationship}
 * save-cascade would rewrite relations it never loaded), and the
 * {@code TravelerRef} node is the lightweight applicative reference merged on
 * demand, never a copy of an identity-service {@code User}.
 *
 * <p>{@code id} on the relation is a surrogate UUID assigned at creation. Its
 * only job today is to let {@link #give} tell "I just created this relation"
 * from "it already existed" inside a single atomic {@code MERGE}, which is
 * what makes the "one feedback per traveler per destination" rule a single
 * statement rather than a racy read-then-write. Neo4j Community cannot
 * enforce relationship uniqueness natively (same documented gap as
 * {@code SUBSCRIBED}, ADR §3), so this narrows the race window as far as
 * Community allows without closing it entirely.</p>
 *
 * <p>Participation read: {@link #hasActiveSubscription} is a read-only
 * lookup over {@code SUBSCRIBED} that lives here (not in
 * {@code SubscriptionRepository}, which is owned by the subscription/payment
 * work) — only status {@code ACTIVE} counts; a {@code PENDING_PAYMENT} or
 * {@code CANCELLED} relation is not participation.</p>
 *
 * No business logic lives here. Every read filters {@code d.deletedAt IS
 * NULL} on the destination side in its own Cypher, so a soft-deleted
 * destination's feedback disappears from every view without the relation
 * being touched.
 */
@Repository
public class FeedbackRepository {

    private static final String HAS_ACTIVE_SUBSCRIPTION_QUERY = """
            MATCH (t:TravelerRef {userId: $travelerId})-[s:SUBSCRIBED {status: 'ACTIVE'}]->(d:Destination)
            WHERE d.id = $destinationId AND d.deletedAt IS NULL
            RETURN count(s) AS activeCount
            """;

    private static final String GIVE_QUERY = """
            MATCH (d:Destination) WHERE d.id = $destinationId AND d.deletedAt IS NULL
            MERGE (t:TravelerRef {userId: $travelerId})
            MERGE (t)-[f:GAVE_FEEDBACK]->(d)
              ON CREATE SET f.id = $feedbackId, f.rating = $rating, f.comment = $comment,
                            f.createdAt = $createdAt
            RETURN f.id = $feedbackId AS created
            """;

    private static final String RETURN_CLAUSE = """
            RETURN f.id AS id, t.userId AS travelerId, d.id AS destinationId, d.name AS destinationName,
                   d.country AS destinationCountry, d.endDate AS destinationEndDate,
                   f.rating AS rating, f.comment AS comment, f.createdAt AS createdAt
            ORDER BY f.createdAt DESC
            """;

    private static final String FIND_FOR_DESTINATION_QUERY = """
            MATCH (t:TravelerRef)-[f:GAVE_FEEDBACK]->(d:Destination)
            WHERE d.id = $destinationId AND d.deletedAt IS NULL
            """ + RETURN_CLAUSE;

    private static final String FIND_ONE_QUERY = """
            MATCH (t:TravelerRef {userId: $travelerId})-[f:GAVE_FEEDBACK]->(d:Destination)
            WHERE d.id = $destinationId AND d.deletedAt IS NULL
            """ + RETURN_CLAUSE;

    private static final String FIND_FOR_TRAVELER_QUERY = """
            MATCH (t:TravelerRef {userId: $travelerId})-[f:GAVE_FEEDBACK]->(d:Destination)
            WHERE d.deletedAt IS NULL
            """ + RETURN_CLAUSE;

    private static final String FIND_ALL_QUERY = """
            MATCH (t:TravelerRef)-[f:GAVE_FEEDBACK]->(d:Destination)
            WHERE d.deletedAt IS NULL
            """ + RETURN_CLAUSE;

    private final Neo4jClient neo4jClient;

    public FeedbackRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Whether {@code travelerId} holds an {@code ACTIVE} subscription for
     * {@code destinationId} (read-only over {@code SUBSCRIBED}).
     */
    public boolean hasActiveSubscription(UUID travelerId, UUID destinationId) {
        Long count = neo4jClient.query(HAS_ACTIVE_SUBSCRIPTION_QUERY)
                .bindAll(idParams(travelerId, destinationId))
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("activeCount").asLong())
                .one()
                .orElse(0L);
        return count > 0;
    }

    /**
     * Create the traveler's feedback on {@code destinationId}, unless one
     * already exists. Assumes the destination exists/is active and that the
     * participation rules were already checked by the caller.
     *
     * @return {@code true} if a new relation was created, {@code false} if
     *         the traveler had already given feedback (nothing is modified in
     *         that case — feedback is immutable)
     */
    public boolean give(UUID travelerId, UUID destinationId, int rating, String comment,
                         OffsetDateTime createdAt) {
        Map<String, Object> params = idParams(travelerId, destinationId);
        params.put("feedbackId", UUID.randomUUID().toString());
        params.put("rating", rating);
        params.put("comment", comment);
        params.put("createdAt", createdAt);
        return neo4jClient.query(GIVE_QUERY)
                .bindAll(params)
                .fetchAs(Boolean.class)
                .mappedBy((typeSystem, record) -> record.get("created").asBoolean())
                .one()
                .orElseThrow(() -> new IllegalStateException(
                        "Feedback query returned no row for destination " + destinationId));
    }

    /** Every feedback on a single active destination, newest first. */
    public List<FeedbackView> findForDestination(UUID destinationId) {
        return neo4jClient.query(FIND_FOR_DESTINATION_QUERY)
                .bindAll(Map.of("destinationId", destinationId.toString()))
                .fetchAs(FeedbackView.class)
                .mappedBy((typeSystem, record) -> toView(record))
                .all()
                .stream()
                .toList();
    }

    /** The (at most one) feedback a traveler gave on a destination. */
    public Optional<FeedbackView> findOne(UUID travelerId, UUID destinationId) {
        return neo4jClient.query(FIND_ONE_QUERY)
                .bindAll(idParams(travelerId, destinationId))
                .fetchAs(FeedbackView.class)
                .mappedBy((typeSystem, record) -> toView(record))
                .one();
    }

    /** Every feedback given by one traveler, across every active destination, newest first. */
    public List<FeedbackView> findForTraveler(UUID travelerId) {
        return neo4jClient.query(FIND_FOR_TRAVELER_QUERY)
                .bindAll(Map.of("travelerId", travelerId.toString()))
                .fetchAs(FeedbackView.class)
                .mappedBy((typeSystem, record) -> toView(record))
                .all()
                .stream()
                .toList();
    }

    /** Every feedback on every active destination, newest first (admin history). */
    public List<FeedbackView> findAll() {
        return neo4jClient.query(FIND_ALL_QUERY)
                .fetchAs(FeedbackView.class)
                .mappedBy((typeSystem, record) -> toView(record))
                .all()
                .stream()
                .toList();
    }

    private static FeedbackView toView(Record record) {
        return new FeedbackView(
                UUID.fromString(record.get("id").asString()),
                UUID.fromString(record.get("travelerId").asString()),
                UUID.fromString(record.get("destinationId").asString()),
                record.get("destinationName").asString(),
                record.get("destinationCountry").asString(),
                record.get("destinationEndDate").isNull() ? null : record.get("destinationEndDate").asLocalDate(),
                record.get("rating").asInt(),
                record.get("comment").isNull() ? null : record.get("comment").asString(),
                record.get("createdAt").isNull() ? null : record.get("createdAt").asOffsetDateTime());
    }

    private static Map<String, Object> idParams(UUID travelerId, UUID destinationId) {
        Map<String, Object> params = new HashMap<>();
        params.put("travelerId", travelerId.toString());
        params.put("destinationId", destinationId.toString());
        return params;
    }
}
