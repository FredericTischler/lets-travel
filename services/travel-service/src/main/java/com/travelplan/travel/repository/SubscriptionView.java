package com.travelplan.travel.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Raw {@code SUBSCRIBED} relation row, as read directly from Neo4j by
 * {@link SubscriptionRepository} — one traveler/destination pair. Used both
 * for the result of a subscribe/unsubscribe action and for the manager-facing
 * subscriber list of a single destination (where the destination itself is
 * already known from the path, so it is not repeated here).
 *
 * <p>{@code status} is the <b>effective</b> status: a stored
 * {@code PENDING_PAYMENT} whose {@code expiresAt} has passed is reported as
 * {@code EXPIRED} (derived on read, never written — see
 * docs/lets-travel-architecture-decisions.md §4 addendum).</p>
 *
 * <p>{@code id}, {@code expiresAt}, {@code paymentId}, {@code amount} and
 * {@code currency} exist only on relations created since Phase 4 (the payment
 * flow); they are {@code null} on older/free ones. {@code amount}/
 * {@code currency} are what the destination cost at subscribe time — the
 * reference the confirmed payment is checked against.</p>
 *
 * Not an API type: {@code SubscriptionService} maps this to
 * {@link com.travelplan.travel.dto.SubscriptionResponse}.
 */
public record SubscriptionView(UUID destinationId, UUID travelerId, String status,
                                OffsetDateTime subscribedAt, OffsetDateTime cancelledAt,
                                UUID id, OffsetDateTime expiresAt, UUID paymentId,
                                BigDecimal amount, String currency) {

    /** Pre-payment shape, kept so callers that only know the original five fields still compile. */
    public SubscriptionView(UUID destinationId, UUID travelerId, String status,
                             OffsetDateTime subscribedAt, OffsetDateTime cancelledAt) {
        this(destinationId, travelerId, status, subscribedAt, cancelledAt, null, null, null, null, null);
    }
}
