package com.travelplan.payment.controller;

import com.travelplan.payment.dto.CreateStripePaymentRequest;
import com.travelplan.payment.dto.StripePaymentResponse;
import com.travelplan.payment.service.StripePaymentService;
import com.travelplan.payment.service.TokenValidationService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Stripe-backed payments (docs/sujet.md §2).
 *
 * No business logic here — all decisions are delegated to
 * {@link StripePaymentService}. Same role-and-ownership protection as
 * {@link PaymentController} (see its class javadoc): any of the three
 * known roles may create a Stripe payment for themselves, only an
 * {@code ADMIN} may create one on behalf of another user.
 */
@RestController
@RequestMapping("/payments/stripe")
public class StripePaymentController {

    private final StripePaymentService stripePaymentService;
    private final TokenValidationService tokenValidationService;

    public StripePaymentController(StripePaymentService stripePaymentService,
                                    TokenValidationService tokenValidationService) {
        this.stripePaymentService = stripePaymentService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Create a Stripe PaymentIntent and a corresponding {@code PENDING}
     * payment record. Requires a valid Bearer token whose caller is the
     * payment's owner or an {@code ADMIN}.
     *
     * @return 201 Created with the created payment plus the PaymentIntent's
     *         {@code client_secret}, 400 if the request body fails validation,
     *         401/403 per {@link TokenValidationService#requireAnyRole}/
     *         {@link TokenValidationService#requireOwnerOrAdmin},
     *         502 if the call to Stripe's API fails
     */
    @PostMapping
    public ResponseEntity<StripePaymentResponse> create(
            @Valid @RequestBody CreateStripePaymentRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRole(authorizationHeader);
        tokenValidationService.requireOwnerOrAdmin(claims, request.getUserId());
        StripePaymentResponse created = stripePaymentService.createPaymentIntent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
