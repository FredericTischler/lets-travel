package com.travelplan.travel.exception;

import java.util.UUID;

/**
 * Thrown when a caller who is authenticated and holds a known role tries to
 * give feedback on a destination they did not participate in (no
 * {@code ACTIVE} subscription). Mapped to HTTP 403 by
 * {@link GlobalExceptionHandler}: the caller is not entitled to this action
 * on this resource, whatever the destination's dates. See
 * docs/lets-travel-architecture-decisions.md §5 addendum.
 */
public class FeedbackNotAllowedException extends RuntimeException {

    public FeedbackNotAllowedException(UUID travelerId, UUID destinationId) {
        super("Traveler " + travelerId + " did not participate in destination " + destinationId
                + ", only participants may give feedback");
    }
}
