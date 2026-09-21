package com.travelplan.payment.controller;

import com.travelplan.payment.dto.CreatePayPalPaymentRequest;
import com.travelplan.payment.dto.PayPalPaymentResponse;
import com.travelplan.payment.dto.PaymentResponse;
import com.travelplan.payment.service.PayPalPaymentService;
import com.travelplan.payment.service.TokenValidationService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for PayPal-backed payments (docs/sujet.md §2).
 *
 * No business logic here — all decisions are delegated to
 * {@link PayPalPaymentService}. Same role-and-ownership protection as
 * {@link PaymentController} for {@code create} (see its class javadoc).
 * {@code capture} takes no {@code userId}, only a provider {@code orderId}: the
 * owner is resolved from the payment row by {@link PayPalPaymentService}, which
 * applies the ownership rule before calling PayPal and answers a non-owner with
 * the same 404 as an unknown order (security audit G2 — an order id is not a
 * credential).
 */
@RestController
@RequestMapping("/payments/paypal")
public class PayPalPaymentController {

    private final PayPalPaymentService payPalPaymentService;
    private final TokenValidationService tokenValidationService;

    public PayPalPaymentController(PayPalPaymentService payPalPaymentService,
                                    TokenValidationService tokenValidationService) {
        this.payPalPaymentService = payPalPaymentService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Create a PayPal Order and a corresponding {@code PENDING} payment
     * record. Requires a valid Bearer token whose caller is the payment's
     * owner or an {@code ADMIN}.
     *
     * @return 201 Created with the created payment plus the order's
     *         approval URL, 400 if the request body fails validation,
     *         401/403 per {@link TokenValidationService#requireAnyRole}/
     *         {@link TokenValidationService#requireOwnerOrAdmin},
     *         502 if the call to PayPal's API fails
     */
    @PostMapping
    public ResponseEntity<PayPalPaymentResponse> create(
            @Valid @RequestBody CreatePayPalPaymentRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRole(authorizationHeader);
        tokenValidationService.requireOwnerOrAdmin(claims, request.getUserId());
        PayPalPaymentResponse created = payPalPaymentService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Capture a previously-created, payer-approved PayPal Order. Requires a
     * valid Bearer token carrying a recognized role, whose caller is the
     * payment's owner or an {@code ADMIN} — see class javadoc.
     *
     * @return 200 with the updated payment (COMPLETED if the capture
     *         succeeded), 404 if no payment has this order id or it belongs to
     *         another user (indistinguishable), 409 if that
     *         payment's status is already terminal, 502 if PayPal's capture
     *         call fails (the payment is transitioned to FAILED first),
     *         401/403 per {@link TokenValidationService#requireAnyRole}
     */
    @PostMapping("/{orderId}/capture")
    public ResponseEntity<PaymentResponse> capture(
            @PathVariable String orderId,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRole(authorizationHeader);
        return ResponseEntity.ok(payPalPaymentService.captureOrder(
                orderId, tokenValidationService.callerId(claims), tokenValidationService.isAdmin(claims)));
    }
}
