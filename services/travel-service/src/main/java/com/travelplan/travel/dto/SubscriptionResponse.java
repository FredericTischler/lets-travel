package com.travelplan.travel.dto;

import com.travelplan.travel.repository.SubscriptionView;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for a single {@code SUBSCRIBED} relation — used by
 * {@code POST /destinations/{id}/subscriptions} (the created subscription)
 * and {@code GET /destinations/{id}/subscriptions} (the manager/admin
 * subscriber list, one row per traveler/status).
 */
public class SubscriptionResponse {

    private final UUID destinationId;
    private final UUID travelerId;
    private final String status;
    private final OffsetDateTime subscribedAt;
    private final OffsetDateTime cancelledAt;

    private SubscriptionResponse(UUID destinationId, UUID travelerId, String status,
                                  OffsetDateTime subscribedAt, OffsetDateTime cancelledAt) {
        this.destinationId = destinationId;
        this.travelerId = travelerId;
        this.status = status;
        this.subscribedAt = subscribedAt;
        this.cancelledAt = cancelledAt;
    }

    public static SubscriptionResponse from(SubscriptionView view) {
        return new SubscriptionResponse(
                view.destinationId(), view.travelerId(), view.status(), view.subscribedAt(), view.cancelledAt());
    }

    public UUID getDestinationId() {
        return destinationId;
    }

    public UUID getTravelerId() {
        return travelerId;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getSubscribedAt() {
        return subscribedAt;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }
}
