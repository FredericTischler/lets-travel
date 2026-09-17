package com.travelplan.payment.controller;

import com.travelplan.payment.service.StripePaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Stripe's {@code payment_intent.succeeded} /
 * {@code payment_intent.payment_failed} webhook (docs/sujet.md §2).
 *
 * No business logic here — signature verification and the status transition
 * are both delegated to {@link StripePaymentService#handleWebhookEvent}.
 *
 * <p><b>Deliberately NOT behind this service's Bearer/ADMIN-role RBAC</b>
 * (unlike every other controller here, which calls
 * {@code TokenValidationService.requireValidToken} — see
 * {@link PaymentController} class javadoc): the caller is Stripe itself, not
 * an identity-service-authenticated user, so there is no JWT to validate.
 * Authenticity is instead established by verifying the {@code Stripe-Signature}
 * header against this service's webhook signing secret (see
 * {@link StripePaymentService#handleWebhookEvent}) — that check plays the
 * same "is this caller who it claims to be" role the JWT check plays
 * everywhere else on this service.</p>
 */
@RestController
@RequestMapping("/webhooks/stripe")
public class StripeWebhookController {

    private final StripePaymentService stripePaymentService;

    public StripeWebhookController(StripePaymentService stripePaymentService) {
        this.stripePaymentService = stripePaymentService;
    }

    /**
     * Receive and process a Stripe webhook event.
     *
     * <p>{@code payload} is bound as the raw request body string (not parsed
     * into a DTO): Stripe's signature is computed over the exact bytes it
     * sent, so any re-serialization here would break verification.</p>
     *
     * @return 200 OK once the event has been verified and processed (or
     *         accepted-and-ignored for an event type/reference this service
     *         does not act on — see {@link StripePaymentService} class
     *         javadoc), 400 if signature verification fails
     */
    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(name = "Stripe-Signature") String sigHeader) {
        stripePaymentService.handleWebhookEvent(payload, sigHeader);
        return ResponseEntity.ok().build();
    }
}
