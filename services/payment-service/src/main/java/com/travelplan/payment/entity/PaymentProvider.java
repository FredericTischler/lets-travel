package com.travelplan.payment.entity;

/**
 * Origin of a {@link Payment}.
 *
 * {@code MANUAL} is the historical, pre-existing payment path
 * (see {@code CreateManualPaymentRequest}) and remains the default for any
 * row that predates this enum (V3__add_provider.sql backfills every existing
 * row to {@code MANUAL}). {@code STRIPE} and {@code PAYPAL} are set by
 * {@link com.travelplan.payment.service.StripePaymentService} and
 * {@link com.travelplan.payment.service.PayPalPaymentService} respectively.
 */
public enum PaymentProvider {
    MANUAL,
    STRIPE,
    PAYPAL
}
