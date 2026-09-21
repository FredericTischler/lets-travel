package com.travelplan.travel.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Raw {@code SUBSCRIBED} relation row for a single traveler's own history,
 * as read directly from Neo4j by {@link SubscriptionRepository} — includes a
 * summary of the destination (unlike {@link SubscriptionView}, which is used
 * where the destination is already known from the request path) so the
 * traveler's personal stats page does not need a second round trip per row.
 *
 * <p>{@code status} is the effective status (a lapsed {@code PENDING_PAYMENT}
 * reads as {@code EXPIRED}), same rule as {@link SubscriptionView}.
 * {@code subscriptionId}/{@code paymentId}/{@code expiresAt} are {@code null}
 * on relations that predate the payment flow.</p>
 *
 * Not an API type: {@code SubscriptionService} maps this to
 * {@link com.travelplan.travel.dto.TravelerSubscriptionResponse}.
 */
public record TravelerSubscriptionView(UUID destinationId, String destinationName, String destinationCountry,
                                        LocalDate destinationStartDate, String status,
                                        OffsetDateTime subscribedAt, OffsetDateTime cancelledAt,
                                        UUID subscriptionId, UUID paymentId, OffsetDateTime expiresAt) {

    /** Pre-payment shape, kept so callers that only know the original seven fields still compile. */
    public TravelerSubscriptionView(UUID destinationId, String destinationName, String destinationCountry,
                                     LocalDate destinationStartDate, String status,
                                     OffsetDateTime subscribedAt, OffsetDateTime cancelledAt) {
        this(destinationId, destinationName, destinationCountry, destinationStartDate, status,
                subscribedAt, cancelledAt, null, null, null);
    }
}
