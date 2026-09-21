package com.travelplan.travel.exception;

import java.util.UUID;

/**
 * Thrown when a subscription request conflicts with the current state of
 * the destination or the caller's existing subscription. Mapped to HTTP 409
 * by {@link GlobalExceptionHandler} — a 400 would be wrong here (the request
 * body/path is well-formed), a 404 would be wrong too (the destination
 * exists) — this is a state conflict, same category "already exists" /
 * "no longer possible" the HTTP spec reserves 409 for.
 *
 * <p>Same "business rule checked in the service, not via annotations"
 * approach as {@link InvalidDestinationRequestException}; static factories
 * document the three distinct cases this phase decided deserve 409 rather
 * than three separate exception classes for one HTTP mapping (see
 * docs/lets-travel-architecture-decisions.md §3 addendum for the reasoning
 * behind each case).</p>
 */
public class SubscriptionConflictException extends RuntimeException {

    private SubscriptionConflictException(String message) {
        super(message);
    }

    /**
     * The caller already holds an {@code ACTIVE} subscription for this
     * destination — subscribing again would create a second, redundant
     * active relation (Neo4j Community has no way to enforce this pair's
     * uniqueness natively, see docs/lets-travel-architecture-decisions.md
     * §3, so the check lives here).
     */
    public static SubscriptionConflictException alreadySubscribed(UUID travelerId, UUID destinationId) {
        return new SubscriptionConflictException(
                "Traveler " + travelerId + " already has an active or pending subscription for destination "
                        + destinationId);
    }

    /**
     * Every seat is taken: {@code ACTIVE} subscriptions plus still-valid
     * {@code PENDING_PAYMENT} holds already reach the destination's
     * {@code capacity} (docs/lets-travel-architecture-decisions.md §4 addendum).
     */
    public static SubscriptionConflictException capacityReached(UUID destinationId) {
        return new SubscriptionConflictException(
                "Destination " + destinationId + " is full: no seat left (pending payments hold seats too)");
    }

    /**
     * A payment-result callback does not match the subscription it names
     * (other traveler/destination/payment, or an amount/currency below what
     * the destination cost). The subscription is left untouched.
     */
    public static SubscriptionConflictException paymentMismatch(UUID subscriptionRef, String detail) {
        return new SubscriptionConflictException(
                "Payment does not match subscription " + subscriptionRef + ": " + detail);
    }

    /**
     * A payment was {@code COMPLETED} for a subscription that had meanwhile
     * been cancelled (by the traveler or a manager): the money was taken but
     * there is nothing to activate. Surfaced as a 409 (and logged as an error
     * by the service) so payment-service keeps it flagged for a manual refund.
     */
    public static SubscriptionConflictException paidButCancelled(UUID subscriptionRef) {
        return new SubscriptionConflictException(
                "Subscription " + subscriptionRef + " was cancelled: a completed payment needs a manual refund");
    }

    /**
     * The destination's {@code startDate} is already in the past — there is
     * nothing left to subscribe to.
     */
    public static SubscriptionConflictException travelAlreadyStarted(UUID destinationId) {
        return new SubscriptionConflictException(
                "Destination " + destinationId + " has already started, subscriptions are closed");
    }

    /**
     * Self-service unsubscribe requested less than 3 days before the
     * destination's {@code startDate} — the cutoff the subject requires for
     * traveler-initiated cancellations (see
     * docs/lets-travel-architecture-decisions.md §3). Does not apply to a
     * manager/admin forcing an unsubscribe — see
     * {@code SubscriptionService#forceUnsubscribe}.
     */
    public static SubscriptionConflictException cutoffPeriodExceeded(UUID destinationId) {
        return new SubscriptionConflictException(
                "Cannot unsubscribe from destination " + destinationId
                        + ": less than 3 days remain before its start date");
    }
}
