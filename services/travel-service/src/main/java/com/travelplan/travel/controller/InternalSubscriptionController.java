package com.travelplan.travel.controller;

import com.travelplan.travel.dto.PaymentResultRequest;
import com.travelplan.travel.dto.SubscriptionResponse;
import com.travelplan.travel.service.SubscriptionService;
import com.travelplan.travel.service.TokenValidationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service endpoints of travel-service — not for the dashboard.
 *
 * <p>Today only {@code POST /internal/subscriptions/{subscriptionRef}/payment-result}:
 * payment-service calls it when a subscription-linked payment reaches
 * {@code COMPLETED}/{@code FAILED} (from the admin status PATCH, the Stripe
 * webhook or the PayPal capture), or when an admin re-triggers
 * {@code POST /payments/reconcile-subscriptions}
 * (docs/lets-travel-architecture-decisions.md §4 addendum).</p>
 *
 * <p>Auth: the {@code service:payment} token only
 * ({@link TokenValidationService#requireServiceToken}); no user token — not
 * even an {@code ADMIN}'s — is accepted, so nobody can settle a subscription
 * by hand-calling this. The path lives under {@code /internal/} by
 * convention; it is not hidden from Traefik, the token is the protection.</p>
 */
@RestController
public class InternalSubscriptionController {

    private final SubscriptionService subscriptionService;
    private final TokenValidationService tokenValidationService;

    public InternalSubscriptionController(SubscriptionService subscriptionService,
                                           TokenValidationService tokenValidationService) {
        this.subscriptionService = subscriptionService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Apply a payment outcome to the subscription it settles. Idempotent.
     *
     * @return 200 with the subscription as it is after the call ({@code ACTIVE} after a
     *         {@code COMPLETED}, {@code CANCELLED} after a {@code FAILED}),
     *         400 if the body is invalid,
     *         401 if the Authorization header is missing/invalid/expired,
     *         403 if the token is not the payment-service service token,
     *         404 if no subscription carries this id,
     *         409 if the payment does not match the subscription (other traveler/destination/payment,
     *         amount or currency below the price), or completed for an already cancelled subscription
     */
    @PostMapping("/internal/subscriptions/{subscriptionRef}/payment-result")
    public ResponseEntity<SubscriptionResponse> paymentResult(
            @PathVariable UUID subscriptionRef,
            @Valid @RequestBody PaymentResultRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireServiceToken(authorizationHeader);
        return ResponseEntity.ok(subscriptionService.applyPaymentResult(subscriptionRef, request));
    }
}
