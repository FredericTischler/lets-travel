package com.travelplan.travel.service;

import com.travelplan.travel.dto.PaymentResultRequest;
import com.travelplan.travel.dto.SubscriptionResponse;
import com.travelplan.travel.dto.TravelerSubscriptionResponse;
import com.travelplan.travel.entity.Destination;
import com.travelplan.travel.exception.DestinationNotFoundException;
import com.travelplan.travel.exception.InsufficientRoleException;
import com.travelplan.travel.exception.SubscriptionConflictException;
import com.travelplan.travel.exception.SubscriptionNotFoundException;
import com.travelplan.travel.repository.DestinationRepository;
import com.travelplan.travel.repository.SubscriptionRepository;
import com.travelplan.travel.repository.SubscriptionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for the {@code SUBSCRIBED} relation
 * (docs/lets-travel-architecture-decisions.md §3): a traveler subscribing to
 * / unsubscribing from a {@link Destination} (the subject's "Travel"), with
 * a 3-day cutoff on self-service cancellation, plus the manager/admin-facing
 * subscriber list and force-unsubscribe.
 *
 * Mirrors {@link DestinationService}'s structure: read-only by default,
 * mutating methods opt into {@code @Transactional}. Destination existence/
 * soft-delete/ownership is always resolved via {@link DestinationRepository}
 * (read-only, never mutated here) — this service never touches
 * {@code DestinationService} or the destination's own CRUD.
 *
 * <p><b>Judgment calls made for §3</b> (documented in full in
 * docs/lets-travel-architecture-decisions.md §3 addendum):</p>
 * <ul>
 *   <li>Subscribing twice while already live is a 409
 *       ({@link SubscriptionConflictException#alreadySubscribed}), not a
 *       silent no-op or a 200 — the caller's intent (a new subscription) was
 *       not fulfilled, and the endpoint is not idempotent by design (see
 *       {@link SubscriptionRepository}'s Javadoc on why every subscribe
 *       creates a fresh relation).</li>
 *   <li>Subscribing to a destination whose {@code startDate} has already
 *       passed is also a 409 ({@link SubscriptionConflictException#travelAlreadyStarted})
 *       — the destination exists and is well-formed, but time-wise there is
 *       nothing left to join.</li>
 *   <li>The 3-day cutoff applies only to {@link #unsubscribeSelf} (the
 *       traveler's own cancellation, for their flexibility per the subject).
 *       {@link #forceUnsubscribe} (manager/admin) deliberately does not
 *       enforce it: a manager pulling a traveler close to departure is an
 *       administrative/capacity decision (e.g. a no-show, a policy
 *       violation), not the self-service flexibility case the cutoff exists
 *       to protect — blocking it would remove a tool the subject explicitly
 *       asks managers to have ("unsubscribe travelers from the travel").</li>
 * </ul>
 *
 * <p><b>Paying for a subscription</b> (Phase 4, docs/lets-travel-architecture-decisions.md
 * §4 addendum). A free destination (price null/0) still goes straight to
 * {@code ACTIVE}. A paid one starts as {@code PENDING_PAYMENT}, holds a seat
 * until {@code expiresAt}, and asks payment-service to create the payment — that
 * subscribe flow lives in {@link SubscriptionCheckoutService} (it must run
 * outside any transaction, see its Javadoc). payment-service later calls
 * {@link #applyPaymentResult} through the internal endpoint, moving the
 * subscription to {@code ACTIVE} ({@code COMPLETED}) or {@code CANCELLED}
 * ({@code FAILED}). No distributed transaction: a failure between two steps is
 * compensated or left to reconciliation as described on each method.</p>
 */
@Service
@Transactional(readOnly = true)
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final String PAYMENT_FAILED = "FAILED";

    /** Self-service unsubscribe cutoff — sujet: "3 days before the travel start date". */
    private static final long CUTOFF_DAYS = 3;

    private final SubscriptionRepository subscriptionRepository;
    private final DestinationRepository destinationRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                                DestinationRepository destinationRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.destinationRepository = destinationRepository;
    }

    /**
     * Apply the outcome of a subscription-linked payment reported by
     * payment-service (docs/lets-travel-architecture-decisions.md §4 addendum).
     * Idempotent: payment-service re-sends until it gets a 2xx, and
     * {@code POST /payments/reconcile-subscriptions} can re-send later.
     *
     * <p>The payment is <b>cross-checked</b> against the subscription rather
     * than trusted by reference: same traveler, same destination, the payment
     * this subscription is waiting for (if one was recorded), and — for
     * {@code COMPLETED} — an amount at least the price captured at subscribe
     * time, in the same currency. Without that, a traveler could create a
     * cheap payment of their own carrying a pending subscription's id and
     * activate it. A mismatch is a 409 and leaves the subscription untouched.</p>
     *
     * <ul>
     *   <li>{@code COMPLETED} + {@code PENDING_PAYMENT} (even one whose hold
     *       has lapsed: the money was taken, so it wins over the expiry — the
     *       seat count may then exceed capacity by such late payments, logged
     *       as a warning) -> {@code ACTIVE}.</li>
     *   <li>{@code COMPLETED} + already {@code ACTIVE} -> no-op (replay).</li>
     *   <li>{@code COMPLETED} + {@code CANCELLED} (the traveler or a manager cancelled
     *       while the payment was in flight) -> 409 and an error log: paid but
     *       nothing to activate, needs a manual refund.</li>
     *   <li>{@code FAILED} + {@code PENDING_PAYMENT} -> {@code CANCELLED}; any other
     *       state -> no-op.</li>
     * </ul>
     *
     * @throws SubscriptionNotFoundException if no subscription carries {@code subscriptionRef}
     * @throws SubscriptionConflictException if the payment does not match the subscription, or
     *         it completed for a cancelled one
     */
    @Transactional
    public SubscriptionResponse applyPaymentResult(UUID subscriptionRef, PaymentResultRequest result) {
        SubscriptionView sub = subscriptionRepository.findById(subscriptionRef)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionRef));
        if (!sub.travelerId().equals(result.getUserId())) {
            throw SubscriptionConflictException.paymentMismatch(subscriptionRef, "payment belongs to another traveler");
        }
        if (!sub.destinationId().equals(result.getTravelId())) {
            throw SubscriptionConflictException.paymentMismatch(subscriptionRef, "payment is for another destination");
        }
        if (sub.paymentId() != null && !sub.paymentId().equals(result.getPaymentId())) {
            throw SubscriptionConflictException.paymentMismatch(
                    subscriptionRef, "subscription is waiting for payment " + sub.paymentId());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (PAYMENT_FAILED.equals(result.getStatus())) {
            if (subscriptionRepository.cancelPending(subscriptionRef, result.getPaymentId(), now)) {
                log.info("Subscription {} cancelled: payment {} failed", subscriptionRef, result.getPaymentId());
            }
            return currentView(subscriptionRef);
        }

        if (sub.amount() == null || result.getAmount().compareTo(sub.amount()) < 0) {
            throw SubscriptionConflictException.paymentMismatch(subscriptionRef, "amount is lower than the price");
        }
        if (!result.getCurrency().equalsIgnoreCase(sub.currency())) {
            throw SubscriptionConflictException.paymentMismatch(
                    subscriptionRef, "currency differs from the price's (" + sub.currency() + ")");
        }
        switch (sub.status()) {
            case STATUS_PENDING_PAYMENT -> {
                if (subscriptionRepository.activatePending(subscriptionRef, result.getPaymentId(), now)
                        && sub.expiresAt() != null && sub.expiresAt().isBefore(now)) {
                    log.warn("Subscription {} activated by payment {} AFTER its hold expired: "
                                    + "destination {} may now exceed its capacity",
                            subscriptionRef, result.getPaymentId(), sub.destinationId());
                }
            }
            case STATUS_ACTIVE -> {
                // replay of an already applied result: nothing to do
            }
            default -> {
                log.error("Payment {} COMPLETED for subscription {} which is {}: the traveler paid "
                                + "but has no seat, manual refund required",
                        result.getPaymentId(), subscriptionRef, sub.status());
                throw SubscriptionConflictException.paidButCancelled(subscriptionRef);
            }
        }
        return currentView(subscriptionRef);
    }

    /**
     * Self-service unsubscribe: {@code travelerId} cancels their own live
     * subscription to {@code destinationId}.
     *
     * <p>The 3-day cutoff protects a <i>confirmed</i> booking; cancelling a
     * {@code PENDING_PAYMENT} one (nothing paid yet, no refund at stake) is
     * always allowed, whatever the date.</p>
     *
     * @throws DestinationNotFoundException if the destination does not exist or is soft-deleted
     * @throws SubscriptionConflictException if the subscription is {@code ACTIVE} and fewer than
     *         3 days remain before {@code startDate}
     * @throws SubscriptionNotFoundException if the traveler has no live subscription to cancel
     */
    @Transactional
    public void unsubscribeSelf(UUID destinationId, UUID travelerId) {
        Destination destination = requireActiveDestination(destinationId);
        String liveStatus = subscriptionRepository.findLiveStatus(travelerId, destinationId, OffsetDateTime.now(ZoneOffset.UTC))
                .orElseThrow(() -> new SubscriptionNotFoundException(travelerId, destinationId));
        if (STATUS_ACTIVE.equals(liveStatus)) {
            requireCutoffRespected(destination);
        }
        cancelOrThrow(travelerId, destinationId);
    }

    /**
     * Manager/admin-initiated unsubscribe of a specific traveler. Does not
     * enforce the 3-day cutoff — see the class-level Javadoc.
     *
     * @throws DestinationNotFoundException if the destination does not exist or is soft-deleted
     * @throws InsufficientRoleException if {@code isAdmin} is false and the
     *         destination's {@code managerId} is not {@code callerId}
     * @throws SubscriptionNotFoundException if {@code travelerId} has no live subscription to cancel
     */
    @Transactional
    public void forceUnsubscribe(UUID destinationId, UUID travelerId, UUID callerId, boolean isAdmin) {
        Destination destination = requireActiveDestination(destinationId);
        requireOwnership(destination, callerId, isAdmin);
        cancelOrThrow(travelerId, destinationId);
    }

    /**
     * List every subscription (any status) for {@code destinationId} —
     * restricted to the destination's own manager or an admin.
     *
     * @throws DestinationNotFoundException if the destination does not exist or is soft-deleted
     * @throws InsufficientRoleException if {@code isAdmin} is false and the
     *         destination's {@code managerId} is not {@code callerId}
     */
    public List<SubscriptionResponse> listSubscribers(UUID destinationId, UUID callerId, boolean isAdmin) {
        Destination destination = requireActiveDestination(destinationId);
        requireOwnership(destination, callerId, isAdmin);
        return subscriptionRepository.findForDestination(destinationId, OffsetDateTime.now(ZoneOffset.UTC)).stream()
                .map(SubscriptionResponse::from)
                .toList();
    }

    /**
     * A traveler's own subscription history (any status), across every
     * active destination. Any role, self only — the caller can only ever
     * request their own history, there is no {@code travelerId} parameter.
     */
    public List<TravelerSubscriptionResponse> findMySubscriptions(UUID travelerId) {
        return subscriptionRepository.findForTraveler(travelerId, OffsetDateTime.now(ZoneOffset.UTC)).stream()
                .map(TravelerSubscriptionResponse::from)
                .toList();
    }

    private Destination requireActiveDestination(UUID destinationId) {
        return destinationRepository.findActiveById(destinationId)
                .orElseThrow(() -> new DestinationNotFoundException(destinationId));
    }

    private SubscriptionResponse currentView(UUID subscriptionRef) {
        return subscriptionRepository.findById(subscriptionRef)
                .map(SubscriptionResponse::from)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionRef));
    }

    private void cancelOrThrow(UUID travelerId, UUID destinationId) {
        boolean cancelled = subscriptionRepository.cancelLive(travelerId, destinationId, OffsetDateTime.now(ZoneOffset.UTC));
        if (!cancelled) {
            throw new SubscriptionNotFoundException(travelerId, destinationId);
        }
    }

    private void requireCutoffRespected(Destination destination) {
        if (destination.getStartDate() == null) {
            return;
        }
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).plusDays(CUTOFF_DAYS);
        if (destination.getStartDate().isBefore(cutoff)) {
            throw SubscriptionConflictException.cutoffPeriodExceeded(destination.getId());
        }
    }

    /**
     * Same ownership rule as {@code DestinationService#requireOwnership}
     * (docs/lets-travel-architecture-decisions.md §2): {@code ADMIN} always
     * passes, otherwise the caller must be the destination's own
     * {@code managerId}.
     */
    private void requireOwnership(Destination destination, UUID callerId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        if (destination.getManagerId() == null || !destination.getManagerId().equals(callerId)) {
            throw new InsufficientRoleException("Not allowed to manage another manager's travel");
        }
    }
}
