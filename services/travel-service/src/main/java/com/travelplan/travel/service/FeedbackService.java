package com.travelplan.travel.service;

import com.travelplan.travel.dto.FeedbackResponse;
import com.travelplan.travel.dto.GiveFeedbackRequest;
import com.travelplan.travel.entity.Destination;
import com.travelplan.travel.exception.DestinationNotFoundException;
import com.travelplan.travel.exception.FeedbackConflictException;
import com.travelplan.travel.exception.FeedbackNotAllowedException;
import com.travelplan.travel.exception.InsufficientRoleException;
import com.travelplan.travel.repository.DestinationRepository;
import com.travelplan.travel.repository.FeedbackRepository;
import com.travelplan.travel.repository.FeedbackView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for the {@code GAVE_FEEDBACK} relation
 * (docs/lets-travel-architecture-decisions.md §5): a traveler rating a
 * {@link Destination} (the subject's "Travel") they participated in, and the
 * quality-control views over that feedback.
 *
 * Same shape as {@link SubscriptionService}: read-only by default, the
 * mutating method opts into {@code @Transactional}; destination existence/
 * soft-delete/ownership is resolved through {@link DestinationRepository}
 * (never mutated here).
 *
 * <p><b>Judgment calls</b> (documented in full in
 * docs/lets-travel-architecture-decisions.md §5 addendum "feedback"):</p>
 * <ul>
 *   <li><b>Participation</b> = an {@code ACTIVE} subscription on the
 *       destination <em>and</em> the destination's {@code endDate} strictly in
 *       the past. No active subscription (never subscribed, cancelled,
 *       force-unsubscribed, or a pending-payment one) is a 403; a participant
 *       whose trip has not ended is a 409.</li>
 *   <li>One feedback per traveler per destination (409 on a second one);
 *       feedback is immutable — no edit, no delete endpoint.</li>
 *   <li>Visibility: the destination's own manager or an admin see a
 *       destination's feedback; a traveler sees only their own; the global
 *       list is admin-only.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final DestinationRepository destinationRepository;

    public FeedbackService(FeedbackRepository feedbackRepository, DestinationRepository destinationRepository) {
        this.feedbackRepository = feedbackRepository;
        this.destinationRepository = destinationRepository;
    }

    /**
     * Record {@code travelerId}'s feedback on {@code destinationId}. The
     * traveler is always the JWT subject, resolved by the controller.
     *
     * @throws DestinationNotFoundException if the destination does not exist or is soft-deleted
     * @throws FeedbackNotAllowedException if the traveler holds no {@code ACTIVE} subscription on it
     * @throws FeedbackConflictException if the destination has not ended yet, or the
     *         traveler already gave feedback on it
     */
    @Transactional
    public FeedbackResponse give(UUID destinationId, UUID travelerId, GiveFeedbackRequest request) {
        Destination destination = requireActiveDestination(destinationId);
        if (!feedbackRepository.hasActiveSubscription(travelerId, destinationId)) {
            throw new FeedbackNotAllowedException(travelerId, destinationId);
        }
        LocalDate endDate = destination.getEndDate();
        if (endDate == null || !endDate.isBefore(LocalDate.now())) {
            throw FeedbackConflictException.travelNotFinished(destinationId);
        }

        // Plain text, stored as typed apart from surrounding whitespace — never HTML-encoded
        // or stripped of markup here: escaping is the renderer's job (ADR §5 addendum).
        String comment = request.getComment() == null ? null : request.getComment().strip();
        boolean created = feedbackRepository.give(
                travelerId, destinationId, request.getRating(), comment, OffsetDateTime.now());
        if (!created) {
            throw FeedbackConflictException.alreadyGiven(travelerId, destinationId);
        }
        return feedbackRepository.findOne(travelerId, destinationId)
                .map(FeedbackResponse::from)
                .orElseThrow(() -> new IllegalStateException(
                        "Feedback just created for destination " + destinationId + " could not be read back"));
    }

    /**
     * Every feedback on {@code destinationId} — restricted to the
     * destination's own manager or an admin (quality control).
     *
     * @throws DestinationNotFoundException if the destination does not exist or is soft-deleted
     * @throws InsufficientRoleException if {@code isAdmin} is false and the
     *         destination's {@code managerId} is not {@code callerId}
     */
    public List<FeedbackResponse> listForDestination(UUID destinationId, UUID callerId, boolean isAdmin) {
        Destination destination = requireActiveDestination(destinationId);
        requireOwnership(destination, callerId, isAdmin);
        return feedbackRepository.findForDestination(destinationId).stream()
                .map(FeedbackResponse::from)
                .toList();
    }

    /**
     * The caller's own feedback, across every active destination. Self only:
     * there is no {@code travelerId} parameter.
     */
    public List<FeedbackResponse> listMine(UUID travelerId) {
        return feedbackRepository.findForTraveler(travelerId).stream()
                .map(FeedbackResponse::from)
                .toList();
    }

    /** Every feedback on every active destination — the caller (an admin) is checked by the controller. */
    public List<FeedbackResponse> listAll() {
        return feedbackRepository.findAll().stream()
                .map(FeedbackResponse::from)
                .toList();
    }

    private Destination requireActiveDestination(UUID destinationId) {
        return destinationRepository.findActiveById(destinationId)
                .orElseThrow(() -> new DestinationNotFoundException(destinationId));
    }

    /**
     * Same ownership rule as {@code SubscriptionService#requireOwnership}
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
