package com.travelplan.travel.exception;

import java.util.UUID;

/**
 * Thrown when no {@code ACTIVE} {@code SUBSCRIBED} relation exists for a
 * given traveler/destination pair — either the traveler self-unsubscribing
 * from a destination they were never subscribed to (or already cancelled),
 * or a manager/admin trying to force-unsubscribe a traveler who is not
 * currently an active subscriber.
 *
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler} — same "absence looks
 * like absence" philosophy as {@link DestinationNotFoundException}.
 */
public class SubscriptionNotFoundException extends RuntimeException {

    public SubscriptionNotFoundException(UUID travelerId, UUID destinationId) {
        super("No active subscription found for traveler " + travelerId + " on destination " + destinationId);
    }
}
