package com.travelplan.travel.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Raw {@code SUBSCRIBED} relation row, as read directly from Neo4j by
 * {@link SubscriptionRepository} — one traveler/destination pair. Used both
 * for the result of a subscribe/unsubscribe action and for the manager-facing
 * subscriber list of a single destination (where the destination itself is
 * already known from the path, so it is not repeated here).
 *
 * Not an API type: {@code SubscriptionService} maps this to
 * {@link com.travelplan.travel.dto.SubscriptionResponse}.
 */
public record SubscriptionView(UUID destinationId, UUID travelerId, String status,
                                OffsetDateTime subscribedAt, OffsetDateTime cancelledAt) {
}
