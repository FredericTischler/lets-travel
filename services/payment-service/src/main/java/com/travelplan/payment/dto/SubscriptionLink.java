package com.travelplan.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;

import java.util.UUID;

/**
 * Optional link from a payment to the travel subscription it settles
 * (docs/lets-travel-architecture-decisions.md §4): {@code travelId} is the
 * Destination id, {@code subscriptionRef} the applicative id of the
 * {@code SUBSCRIBED} relation in travel-service. Base class of the three
 * create-payment requests (manual/Stripe/PayPal) so the rule lives once.
 *
 * <p>Both fields or neither: a half-linked payment could never be
 * confirmed back to travel-service (which needs both to cross-check the
 * subscription), so it is rejected with a 400 rather than silently stored.</p>
 */
public abstract class SubscriptionLink {

    private UUID travelId;

    private UUID subscriptionRef;

    public UUID getTravelId() {
        return travelId;
    }

    public void setTravelId(UUID travelId) {
        this.travelId = travelId;
    }

    public UUID getSubscriptionRef() {
        return subscriptionRef;
    }

    public void setSubscriptionRef(UUID subscriptionRef) {
        this.subscriptionRef = subscriptionRef;
    }

    /** Whether this request settles a subscription (both link fields present). */
    @JsonIgnore
    public boolean isSubscriptionLinked() {
        return travelId != null && subscriptionRef != null;
    }

    @JsonIgnore
    @AssertTrue(message = "travelId and subscriptionRef must be provided together")
    public boolean isLinkConsistent() {
        return (travelId == null) == (subscriptionRef == null);
    }
}
