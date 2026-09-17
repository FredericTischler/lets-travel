package com.travelplan.payment.controller;

import com.travelplan.payment.dto.CreateStripePaymentRequest;
import com.travelplan.payment.dto.StripePaymentResponse;
import com.travelplan.payment.service.StripePaymentService;
import com.travelplan.payment.service.TokenValidationService;
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
 * {@link StripePaymentService}. Same Bearer/ADMIN-role protection as every
 * other route on this service (see {@link PaymentController} class javadoc)
 * — there is no anonymous use case for creating a payment.
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
     * payment record. Requires a valid Bearer token with the ADMIN role.
     *
     * @return 201 Created with the created payment plus the PaymentIntent's
     *         {@code client_secret}, 400 if the request body fails validation,
     *         401/403 per {@link TokenValidationService#requireValidToken},
     *         502 if the call to Stripe's API fails
     */
    @PostMapping
    public ResponseEntity<StripePaymentResponse> create(
            @Valid @RequestBody CreateStripePaymentRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireValidToken(authorizationHeader);
        StripePaymentResponse created = stripePaymentService.createPaymentIntent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
