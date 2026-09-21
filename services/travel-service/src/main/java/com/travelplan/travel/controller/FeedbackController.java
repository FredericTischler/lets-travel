package com.travelplan.travel.controller;

import com.travelplan.travel.dto.FeedbackResponse;
import com.travelplan.travel.dto.GiveFeedbackRequest;
import com.travelplan.travel.service.FeedbackService;
import com.travelplan.travel.service.TokenValidationService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the {@code GAVE_FEEDBACK} relation
 * (docs/lets-travel-architecture-decisions.md §5): a traveler's rating and
 * comment on a {@code Destination} they participated in, and the
 * quality-control views for managers and admins.
 *
 * No business logic here — decisions are delegated to {@link FeedbackService},
 * the same split as {@link SubscriptionController}. Authorization is the
 * manual first-line check through {@link TokenValidationService}; there is no
 * Spring Security annotation or filter chain in this codebase.
 *
 * <p>Base paths follow the subject's phrasing, as for subscriptions: actions
 * tied to one destination under {@code /destinations/{id}/feedback}, the
 * traveler's own history under {@code /travelers/me/feedback}, the admin's
 * global list under {@code /feedback}.</p>
 */
@RestController
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final TokenValidationService tokenValidationService;

    public FeedbackController(FeedbackService feedbackService, TokenValidationService tokenValidationService) {
        this.feedbackService = feedbackService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Give feedback on a destination, as the caller themselves (the author is
     * always the JWT subject, never client-supplied). Any known role — a
     * manager or admin who travelled is a traveler too.
     *
     * @return 201 Created with the feedback,
     *         400 if {@code rating} is not an integer 1..5 or {@code comment} is blank/too long,
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the token carries no recognized role, or the caller has no
     *         {@code ACTIVE} subscription on the destination (did not participate),
     *         404 if the destination does not exist or is soft-deleted,
     *         409 if the destination has not ended yet, or the caller already gave feedback on it
     */
    @PostMapping("/destinations/{id}/feedback")
    public ResponseEntity<FeedbackResponse> give(
            @PathVariable UUID id,
            @Valid @RequestBody GiveFeedbackRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRoleClaims(authorizationHeader);
        FeedbackResponse created = feedbackService.give(id, tokenValidationService.callerId(claims), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Every feedback on a destination, for quality control. Restricted to the
     * destination's own {@code TRAVEL_MANAGER} or an {@code ADMIN}.
     *
     * @return 200 with the list (empty if none),
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the caller is not an ADMIN/TRAVEL_MANAGER, or is a
     *         TRAVEL_MANAGER who does not own this destination,
     *         404 if the destination does not exist or is soft-deleted
     */
    @GetMapping("/destinations/{id}/feedback")
    public ResponseEntity<List<FeedbackResponse>> listForDestination(
            @PathVariable UUID id,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireManagerOrAdmin(authorizationHeader);
        return ResponseEntity.ok(feedbackService.listForDestination(
                id, tokenValidationService.callerId(claims), tokenValidationService.isAdmin(claims)));
    }

    /**
     * The caller's own feedback, across every active destination. Any known
     * role, self only — there is no way to request another traveler's.
     *
     * @return 200 with the list (empty if none),
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the token carries no recognized role
     */
    @GetMapping("/travelers/me/feedback")
    public ResponseEntity<List<FeedbackResponse>> myFeedback(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRoleClaims(authorizationHeader);
        return ResponseEntity.ok(feedbackService.listMine(tokenValidationService.callerId(claims)));
    }

    /**
     * Every feedback on every active destination — the admin dashboard's
     * "detailed travel history and feedbacks" list. {@code ADMIN} only.
     *
     * @return 200 with the list (empty if none),
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the caller is not an ADMIN
     */
    @GetMapping("/feedback")
    public ResponseEntity<List<FeedbackResponse>> listAll(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireAdmin(authorizationHeader);
        return ResponseEntity.ok(feedbackService.listAll());
    }
}
