package com.travelplan.travel.repository;

import org.neo4j.driver.Record;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for the {@code SUBSCRIBED} relationship
 * (docs/lets-travel-architecture-decisions.md §3, §4): {@code
 * (TravelerRef {userId})-[:SUBSCRIBED {id, status, subscribedAt, cancelledAt,
 * expiresAt, amount, currency, paymentId}]->(Destination)}.
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
 * <p><b>Statuses</b> (Phase 4, docs/lets-travel-architecture-decisions.md §4
 * addendum): stored as {@code ACTIVE}, {@code PENDING_PAYMENT} or
 * {@code CANCELLED}. A {@code PENDING_PAYMENT} carries {@code expiresAt}; once
 * it has passed, the relation is <i>read</i> as {@code EXPIRED} (derived in
 * Cypher, never written) and stops counting toward capacity or blocking a
 * new subscribe. "Live" below means {@code ACTIVE} or a not-yet-expired
 * {@code PENDING_PAYMENT}.</p>
 *
 * No business logic lives here — existence/soft-delete/ownership checks on
 * the destination, the duplicate check, the cutoff and the payment
 * cross-checks are all the caller's ({@code SubscriptionService}'s)
 * responsibility; this class only reads/writes the relation itself. Every
 * read still filters {@code d.deletedAt IS NULL} in its own Cypher as a
 * second line of defense, consistent with the rest of the codebase — with
 * one deliberate exception, {@link #findById}: see its Javadoc.
 */
@Repository
public class SubscriptionRepository {

    /** A relation that currently occupies a seat / blocks a duplicate. */
    private static final String IS_LIVE =
            "(s.status = 'ACTIVE' OR (s.status = 'PENDING_PAYMENT' AND s.expiresAt > $now))";

    private static final String EFFECTIVE_STATUS =
            "CASE WHEN s.status = 'PENDING_PAYMENT' AND s.expiresAt <= $now THEN 'EXPIRED' ELSE s.status END";

    private static final String RETURN_VIEW_COLUMNS = """
            d.id AS destinationId, t.userId AS travelerId, %s AS status,
                   s.subscribedAt AS subscribedAt, s.cancelledAt AS cancelledAt,
                   s.id AS id, s.expiresAt AS expiresAt, s.paymentId AS paymentId,
                   s.amount AS amount, s.currency AS currency
            """;

    private static final String FIND_LIVE_STATUS_QUERY = """
            MATCH (t:TravelerRef {userId: $travelerId})-[s:SUBSCRIBED]->(d:Destination)
            WHERE d.id = $destinationId AND d.deletedAt IS NULL AND\s""" + IS_LIVE + """

            RETURN s.status AS status
            ORDER BY s.subscribedAt DESC
            LIMIT 1
            """;

    /**
     * Single statement so the capacity check and the insert cannot be
     * interleaved by another statement of this service: count the live
     * relations on the destination, and only create the new one if a seat is
     * left. No row is returned when the destination is full. (Neo4j's default
     * read-committed isolation still lets two truly simultaneous statements
     * both see the last seat — a documented residual race, see ADR §4.)
     */
    private static final String SUBSCRIBE_QUERY = """
            MATCH (d:Destination) WHERE d.id = $destinationId AND d.deletedAt IS NULL
            OPTIONAL MATCH (:TravelerRef)-[s:SUBSCRIBED]->(d) WHERE\s""" + IS_LIVE + """

            WITH d, count(s) AS taken
            WHERE d.capacity IS NULL OR taken < d.capacity
            MERGE (t:TravelerRef {userId: $travelerId})
            CREATE (t)-[s:SUBSCRIBED {id: $id, status: $status, subscribedAt: $now, cancelledAt: null,
                                      expiresAt: $expiresAt, amount: $amount, currency: $currency,
                                      paymentId: null}]->(d)
            RETURN\s""" + RETURN_VIEW_COLUMNS.formatted("s.status");

    private static final String ATTACH_PAYMENT_QUERY = """
            MATCH (:TravelerRef)-[s:SUBSCRIBED {id: $id}]->(:Destination)
            SET s.paymentId = $paymentId, s.paymentProvider = $provider
            RETURN count(s) AS updated
            """;

    private static final String CANCEL_LIVE_QUERY = """
            MATCH (t:TravelerRef {userId: $travelerId})-[s:SUBSCRIBED]->(d:Destination)
            WHERE d.id = $destinationId AND d.deletedAt IS NULL AND\s""" + IS_LIVE + """

            SET s.status = 'CANCELLED', s.cancelledAt = $now
            RETURN count(s) AS updated
            """;

    /**
     * Only ever moves a {@code PENDING_PAYMENT} relation (stored status, even
     * if its hold has lapsed — a payment that completes after expiry still
     * wins, see the service Javadoc). {@code expiresAt = null} removes the
     * property: an active subscription no longer expires.
     */
    private static final String ACTIVATE_PENDING_QUERY = """
            MATCH (:TravelerRef)-[s:SUBSCRIBED {id: $id, status: 'PENDING_PAYMENT'}]->(:Destination)
            SET s.status = 'ACTIVE', s.paymentId = $paymentId, s.confirmedAt = $now, s.expiresAt = null
            RETURN count(s) AS updated
            """;

    private static final String CANCEL_PENDING_QUERY = """
            MATCH (:TravelerRef)-[s:SUBSCRIBED {id: $id, status: 'PENDING_PAYMENT'}]->(:Destination)
            SET s.status = 'CANCELLED', s.cancelledAt = $now,
                s.paymentId = coalesce($paymentId, s.paymentId)
            RETURN count(s) AS updated
            """;

    private static final String FIND_BY_ID_QUERY = """
            MATCH (t:TravelerRef)-[s:SUBSCRIBED {id: $id}]->(d:Destination)
            RETURN\s""" + RETURN_VIEW_COLUMNS.formatted("s.status");

    private static final String FIND_FOR_DESTINATION_QUERY = """
            MATCH (t:TravelerRef)-[s:SUBSCRIBED]->(d:Destination)
            WHERE d.id = $destinationId AND d.deletedAt IS NULL
            RETURN\s""" + RETURN_VIEW_COLUMNS.formatted(EFFECTIVE_STATUS) + """
            ORDER BY s.subscribedAt DESC
            """;

    private static final String FIND_FOR_TRAVELER_QUERY = """
            MATCH (t:TravelerRef {userId: $travelerId})-[s:SUBSCRIBED]->(d:Destination)
            WHERE d.deletedAt IS NULL
            RETURN d.id AS destinationId, d.name AS destinationName, d.country AS destinationCountry,
                   d.startDate AS destinationStartDate, %s AS status,
                   s.subscribedAt AS subscribedAt, s.cancelledAt AS cancelledAt,
                   s.id AS subscriptionId, s.paymentId AS paymentId, s.expiresAt AS expiresAt
            ORDER BY s.subscribedAt DESC
            """.formatted(EFFECTIVE_STATUS);

    private final Neo4jClient neo4jClient;

    public SubscriptionRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * The status of {@code travelerId}'s live subscription to
     * {@code destinationId} ({@code ACTIVE} or a not-yet-expired
     * {@code PENDING_PAYMENT}), empty if there is none.
     */
    public Optional<String> findLiveStatus(UUID travelerId, UUID destinationId, OffsetDateTime now) {
        Map<String, Object> params = idParams(travelerId, destinationId);
        params.put("now", now);
        return neo4jClient.query(FIND_LIVE_STATUS_QUERY)
                .bindAll(params)
                .fetchAs(String.class)
                .mappedBy((typeSystem, record) -> record.get("status").asString())
                .first();
    }

    /**
     * Create a new {@code SUBSCRIBED} relation from {@code travelerId}
     * (merged into a {@code TravelerRef} node) to {@code destinationId},
     * unless the destination is already full. Assumes the destination exists,
     * is active, and that the duplicate check has already passed (checked by
     * the caller).
     *
     * @return the created relation, or empty if no seat was left
     */
    public Optional<SubscriptionView> subscribe(NewSubscription subscription, OffsetDateTime now) {
        Map<String, Object> params = idParams(subscription.travelerId(), subscription.destinationId());
        params.put("id", subscription.id().toString());
        params.put("status", subscription.status());
        params.put("now", now);
        params.put("expiresAt", subscription.expiresAt());
        // BigDecimal is stored as a plain string: Neo4j has no decimal type, and this keeps the
        // exact price the traveler was asked to pay (same choice SDN makes for Destination.price).
        params.put("amount", subscription.amount() == null ? null : subscription.amount().toPlainString());
        params.put("currency", subscription.currency());
        return neo4jClient.query(SUBSCRIBE_QUERY)
                .bindAll(params)
                .fetchAs(SubscriptionView.class)
                .mappedBy((typeSystem, record) -> toSubscriptionView(record))
                .first();
    }

    /** Record which payment (and provider) was created for a pending subscription. */
    public void attachPayment(UUID subscriptionId, UUID paymentId, String provider) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", subscriptionId.toString());
        params.put("paymentId", paymentId.toString());
        params.put("provider", provider);
        neo4jClient.query(ATTACH_PAYMENT_QUERY).bindAll(params).fetch().one();
    }

    /**
     * Cancel the traveler's live subscription (active, or pending and not
     * yet expired) for {@code destinationId}, if any. Returns {@code false}
     * (no-op) when none exists, leaving the "not found" decision to the caller.
     */
    public boolean cancelLive(UUID travelerId, UUID destinationId, OffsetDateTime now) {
        Map<String, Object> params = idParams(travelerId, destinationId);
        params.put("now", now);
        return updatedCount(CANCEL_LIVE_QUERY, params) > 0;
    }

    /**
     * {@code PENDING_PAYMENT -> ACTIVE} for the subscription {@code id}, once
     * its payment completed. Returns {@code false} if the relation is not
     * (any more) {@code PENDING_PAYMENT}.
     */
    public boolean activatePending(UUID subscriptionId, UUID paymentId, OffsetDateTime now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", subscriptionId.toString());
        params.put("paymentId", paymentId.toString());
        params.put("now", now);
        return updatedCount(ACTIVATE_PENDING_QUERY, params) > 0;
    }

    /**
     * {@code PENDING_PAYMENT -> CANCELLED} for the subscription {@code id}
     * (payment failed, or the payment could not even be created). {@code paymentId}
     * may be {@code null}. Returns {@code false} if the relation is not
     * {@code PENDING_PAYMENT}.
     */
    public boolean cancelPending(UUID subscriptionId, UUID paymentId, OffsetDateTime now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", subscriptionId.toString());
        params.put("paymentId", paymentId == null ? null : paymentId.toString());
        params.put("now", now);
        return updatedCount(CANCEL_PENDING_QUERY, params) > 0;
    }

    /**
     * The subscription carrying {@code subscriptionId}, with its <b>stored</b>
     * status (an expired hold still reads {@code PENDING_PAYMENT}: the payment
     * callback must be able to see it).
     *
     * <p>Deliberate exception to the "filter {@code d.deletedAt IS NULL} on
     * every hop" rule: this is the lookup behind a payment outcome, and the
     * outcome of a payment that was really made must be recordable even if
     * the destination was soft-deleted meanwhile — otherwise payment-service
     * would get a 404 and re-send it forever. Nothing is <i>exposed</i>
     * through this path: every read endpoint still filters deleted
     * destinations.</p>
     */
    public Optional<SubscriptionView> findById(UUID subscriptionId) {
        return neo4jClient.query(FIND_BY_ID_QUERY)
                .bindAll(Map.of("id", subscriptionId.toString()))
                .fetchAs(SubscriptionView.class)
                .mappedBy((typeSystem, record) -> toSubscriptionView(record))
                .first();
    }

    /**
     * All subscription rows (any status) for a single active destination —
     * the manager/admin-facing subscriber list.
     */
    public List<SubscriptionView> findForDestination(UUID destinationId, OffsetDateTime now) {
        Map<String, Object> params = new HashMap<>();
        params.put("destinationId", destinationId.toString());
        params.put("now", now);
        return neo4jClient.query(FIND_FOR_DESTINATION_QUERY)
                .bindAll(params)
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
    public List<TravelerSubscriptionView> findForTraveler(UUID travelerId, OffsetDateTime now) {
        Map<String, Object> params = new HashMap<>();
        params.put("travelerId", travelerId.toString());
        params.put("now", now);
        return neo4jClient.query(FIND_FOR_TRAVELER_QUERY)
                .bindAll(params)
                .fetchAs(TravelerSubscriptionView.class)
                .mappedBy((typeSystem, record) -> new TravelerSubscriptionView(
                        UUID.fromString(record.get("destinationId").asString()),
                        record.get("destinationName").asString(),
                        record.get("destinationCountry").asString(),
                        record.get("destinationStartDate").isNull()
                                ? null : record.get("destinationStartDate").asLocalDate(),
                        record.get("status").asString(),
                        offsetDateTime(record, "subscribedAt"),
                        offsetDateTime(record, "cancelledAt"),
                        uuid(record, "subscriptionId"),
                        uuid(record, "paymentId"),
                        offsetDateTime(record, "expiresAt")))
                .all()
                .stream()
                .toList();
    }

    private long updatedCount(String query, Map<String, Object> params) {
        return neo4jClient.query(query)
                .bindAll(params)
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("updated").asLong())
                .one()
                .orElse(0L);
    }

    private static SubscriptionView toSubscriptionView(Record record) {
        return new SubscriptionView(
                UUID.fromString(record.get("destinationId").asString()),
                UUID.fromString(record.get("travelerId").asString()),
                record.get("status").asString(),
                offsetDateTime(record, "subscribedAt"),
                offsetDateTime(record, "cancelledAt"),
                uuid(record, "id"),
                offsetDateTime(record, "expiresAt"),
                uuid(record, "paymentId"),
                record.get("amount").isNull() ? null : new BigDecimal(record.get("amount").asString()),
                record.get("currency").isNull() ? null : record.get("currency").asString());
    }

    private static OffsetDateTime offsetDateTime(Record record, String column) {
        return record.get(column).isNull() ? null : record.get(column).asOffsetDateTime();
    }

    private static UUID uuid(Record record, String column) {
        return record.get(column).isNull() ? null : UUID.fromString(record.get(column).asString());
    }

    private static Map<String, Object> idParams(UUID travelerId, UUID destinationId) {
        Map<String, Object> params = new HashMap<>();
        params.put("travelerId", travelerId.toString());
        params.put("destinationId", destinationId.toString());
        return params;
    }

    /**
     * What {@link #subscribe} needs to create a relation. {@code expiresAt},
     * {@code amount} and {@code currency} are {@code null} for a free
     * ({@code ACTIVE}) subscription.
     */
    public record NewSubscription(UUID id, UUID travelerId, UUID destinationId, String status,
                                   OffsetDateTime expiresAt, BigDecimal amount, String currency) {
    }
}
