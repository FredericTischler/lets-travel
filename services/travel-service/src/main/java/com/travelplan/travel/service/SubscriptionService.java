package com.travelplan.travel.service;

import com.travelplan.travel.dto.SubscriptionResponse;
import com.travelplan.travel.dto.TravelerSubscriptionResponse;
import com.travelplan.travel.entity.Destination;
import com.travelplan.travel.exception.DestinationNotFoundException;
import com.travelplan.travel.exception.InsufficientRoleException;
import com.travelplan.travel.exception.SubscriptionConflictException;
import com.travelplan.travel.exception.SubscriptionNotFoundException;
import com.travelplan.travel.repository.DestinationRepository;
import com.travelplan.travel.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * <p><b>Judgment calls made for this phase</b> (documented in full in
 * docs/lets-travel-architecture-decisions.md §3 addendum):</p>
 * <ul>
 *   <li>Subscribing twice while already {@code ACTIVE} is a 409
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
 */
@Service
@Transactional(readOnly = true)
public class SubscriptionService {

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
     * Subscribe {@code travelerId} to {@code destinationId}.
     *
     * @throws DestinationNotFoundException if the destination does not exist or is soft-deleted
     * @throws SubscriptionConflictException if the destination's startDate has
     *         already passed, or the traveler already holds an active subscription
     */
    @Transactional
    public SubscriptionResponse subscribe(UUID destinationId, UUID travelerId) {
        Destination destination = requireActiveDestination(destinationId);
        if (destination.getStartDate() != null && destination.getStartDate().isBefore(LocalDate.now())) {
            throw SubscriptionConflictException.travelAlreadyStarted(destinationId);
        }
        if (subscriptionRepository.hasActiveSubscription(travelerId, destinationId)) {
            throw SubscriptionConflictException.alreadySubscribed(travelerId, destinationId);
        }
        return SubscriptionResponse.from(
                subscriptionRepository.subscribe(travelerId, destinationId, OffsetDateTime.now()));
    }

    /**
     * Self-service unsubscribe: {@code travelerId} cancels their own active
     * subscription to {@code destinationId}, enforcing the 3-day cutoff.
     *
     * @throws DestinationNotFoundException if the destination does not exist or is soft-deleted
     * @throws SubscriptionConflictException if fewer than 3 days remain before {@code startDate}
     * @throws SubscriptionNotFoundException if the traveler has no active subscription to cancel
     */
    @Transactional
    public void unsubscribeSelf(UUID destinationId, UUID travelerId) {
        Destination destination = requireActiveDestination(destinationId);
        requireCutoffRespected(destination);
        cancelOrThrow(travelerId, destinationId);
    }

    /**
     * Manager/admin-initiated unsubscribe of a specific traveler. Does not
     * enforce the 3-day cutoff — see the class-level Javadoc.
     *
     * @throws DestinationNotFoundException if the destination does not exist or is soft-deleted
     * @throws InsufficientRoleException if {@code isAdmin} is false and the
     *         destination's {@code managerId} is not {@code callerId}
     * @throws SubscriptionNotFoundException if {@code travelerId} has no active subscription to cancel
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
        return subscriptionRepository.findForDestination(destinationId).stream()
                .map(SubscriptionResponse::from)
                .toList();
    }

    /**
     * A traveler's own subscription history (any status), across every
     * active destination. Any role, self only — the caller can only ever
     * request their own history, there is no {@code travelerId} parameter.
     */
    public List<TravelerSubscriptionResponse> findMySubscriptions(UUID travelerId) {
        return subscriptionRepository.findForTraveler(travelerId).stream()
                .map(TravelerSubscriptionResponse::from)
                .toList();
    }

    private Destination requireActiveDestination(UUID destinationId) {
        return destinationRepository.findActiveById(destinationId)
                .orElseThrow(() -> new DestinationNotFoundException(destinationId));
    }

    private void cancelOrThrow(UUID travelerId, UUID destinationId) {
        boolean cancelled = subscriptionRepository.cancelActive(travelerId, destinationId, OffsetDateTime.now());
        if (!cancelled) {
            throw new SubscriptionNotFoundException(travelerId, destinationId);
        }
    }

    private void requireCutoffRespected(Destination destination) {
        if (destination.getStartDate() == null) {
            return;
        }
        LocalDate cutoff = LocalDate.now().plusDays(CUTOFF_DAYS);
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
