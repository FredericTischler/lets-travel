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
 * Not an API type: {@code SubscriptionService} maps this to
 * {@link com.travelplan.travel.dto.TravelerSubscriptionResponse}.
 */
public record TravelerSubscriptionView(UUID destinationId, String destinationName, String destinationCountry,
                                        LocalDate destinationStartDate, String status,
                                        OffsetDateTime subscribedAt, OffsetDateTime cancelledAt) {
}
