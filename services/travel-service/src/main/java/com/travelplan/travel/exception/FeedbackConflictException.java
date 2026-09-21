package com.travelplan.travel.exception;

import java.util.UUID;

/**
 * Thrown when a feedback request conflicts with the current state of the
 * destination or of the caller's existing feedback. Mapped to HTTP 409 by
 * {@link GlobalExceptionHandler} — same reasoning as
 * {@link SubscriptionConflictException}: the request is well-formed and the
 * destination exists, it is the state (time, prior feedback) that makes the
 * action impossible. See docs/lets-travel-architecture-decisions.md §5
 * addendum.
 */
public class FeedbackConflictException extends RuntimeException {

    private FeedbackConflictException(String message) {
        super(message);
    }

    /** The destination's {@code endDate} is not in the past yet: nothing to review. */
    public static FeedbackConflictException travelNotFinished(UUID destinationId) {
        return new FeedbackConflictException(
                "Destination " + destinationId + " has not ended yet, feedback is not open");
    }

    /** One feedback per traveler per destination; feedback is immutable. */
    public static FeedbackConflictException alreadyGiven(UUID travelerId, UUID destinationId) {
        return new FeedbackConflictException(
                "Traveler " + travelerId + " has already given feedback for destination " + destinationId);
    }
}
