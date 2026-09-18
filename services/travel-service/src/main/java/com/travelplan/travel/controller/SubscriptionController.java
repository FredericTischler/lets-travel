package com.travelplan.travel.controller;

import com.travelplan.travel.dto.SubscriptionResponse;
import com.travelplan.travel.dto.TravelerSubscriptionResponse;
import com.travelplan.travel.service.SubscriptionService;
import com.travelplan.travel.service.TokenValidationService;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the {@code SUBSCRIBED} relation
 * (docs/lets-travel-architecture-decisions.md §3): subscribing/unsubscribing
 * a traveler to/from a {@code Destination} (the subject's "Travel").
 *
 * No business logic here — all decisions are delegated to
 * {@link SubscriptionService}, exactly the split
 * {@link DestinationController} uses. Bearer token validation goes through
 * {@link TokenValidationService}; exception-to-HTTP mapping is
 * {@link com.travelplan.travel.exception.GlobalExceptionHandler}.
 *
 * <p>Endpoints are split across two base paths, following the subject's own
 * phrasing: subscription actions on a specific destination live under
 * {@code /destinations/{id}/subscriptions} (mirrors
 * {@code /destinations/{id}/transports}), while a traveler's own history —
 * needed for their personal stats page, not tied to one destination — lives
 * under {@code /travelers/me/subscriptions}.</p>
 */
@RestController
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final TokenValidationService tokenValidationService;

    public SubscriptionController(SubscriptionService subscriptionService,
                                   TokenValidationService tokenValidationService) {
        this.subscriptionService = subscriptionService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Subscribe the caller themselves to a destination. The subscriber id is
     * always the caller's own JWT subject — never client-supplied — same
     * principle Phase 1 established for payment-service ownership.
     *
     * @return 201 Created with the new subscription,
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the token carries no recognized role,
     *         404 if the destination does not exist or is soft-deleted,
     *         409 if the destination's startDate has already passed, or the
     *         caller already holds an active subscription for it
     */
    @PostMapping("/destinations/{id}/subscriptions")
    public ResponseEntity<SubscriptionResponse> subscribe(
            @PathVariable UUID id,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRoleClaims(authorizationHeader);
        SubscriptionResponse created = subscriptionService.subscribe(id, tokenValidationService.callerId(claims));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Unsubscribe the caller themselves from a destination, enforcing the
     * 3-day cutoff.
     *
     * @return 204 No Content on success,
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the token carries no recognized role,
     *         404 if the destination does not exist/is soft-deleted, or the
     *         caller has no active subscription to cancel,
     *         409 if fewer than 3 days remain before the destination's startDate
     */
    @DeleteMapping("/destinations/{id}/subscriptions")
    public ResponseEntity<Void> unsubscribe(
            @PathVariable UUID id,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRoleClaims(authorizationHeader);
        subscriptionService.unsubscribeSelf(id, tokenValidationService.callerId(claims));
        return ResponseEntity.noContent().build();
    }

    /**
     * List every subscription (any status) for a destination. Restricted to
     * the destination's own {@code TRAVEL_MANAGER} or an {@code ADMIN}.
     *
     * @return 200 with the list (empty list if none),
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the caller is not an ADMIN/TRAVEL_MANAGER, or is a
     *         TRAVEL_MANAGER who does not own this destination,
     *         404 if the destination does not exist or is soft-deleted
     */
    @GetMapping("/destinations/{id}/subscriptions")
    public ResponseEntity<List<SubscriptionResponse>> listSubscribers(
            @PathVariable UUID id,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireManagerOrAdmin(authorizationHeader);
        List<SubscriptionResponse> subscribers = subscriptionService.listSubscribers(
                id, tokenValidationService.callerId(claims), tokenValidationService.isAdmin(claims));
        return ResponseEntity.ok(subscribers);
    }

    /**
     * Manager/admin-initiated unsubscribe of a specific traveler from a
     * destination. Does not enforce the 3-day cutoff — see
     * {@link SubscriptionService}'s class-level Javadoc for why.
     *
     * @return 204 No Content on success,
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the caller is not an ADMIN/TRAVEL_MANAGER, or is a
     *         TRAVEL_MANAGER who does not own this destination,
     *         404 if the destination does not exist/is soft-deleted, or
     *         {@code travelerId} has no active subscription to cancel
     */
    @DeleteMapping("/destinations/{id}/subscriptions/{travelerId}")
    public ResponseEntity<Void> forceUnsubscribe(
            @PathVariable UUID id,
            @PathVariable UUID travelerId,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireManagerOrAdmin(authorizationHeader);
        subscriptionService.forceUnsubscribe(
                id, travelerId, tokenValidationService.callerId(claims), tokenValidationService.isAdmin(claims));
        return ResponseEntity.noContent().build();
    }

    /**
     * The caller's own subscription history, across every active
     * destination. Any known role, self only — there is no way to request
     * another traveler's history through this endpoint.
     *
     * @return 200 with the list (empty list if none),
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the token carries no recognized role
     */
    @GetMapping("/travelers/me/subscriptions")
    public ResponseEntity<List<TravelerSubscriptionResponse>> mySubscriptions(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRoleClaims(authorizationHeader);
        return ResponseEntity.ok(subscriptionService.findMySubscriptions(tokenValidationService.callerId(claims)));
    }
}
