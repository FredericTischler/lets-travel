package com.travelplan.payment.service;

import java.util.UUID;

/**
 * Published after a payment reaches a terminal status
 * ({@code COMPLETED}/{@code FAILED}) through any of the three paths that can
 * do it: the admin {@code PATCH /payments/{id}/status}, the Stripe webhook and
 * the PayPal capture. Carries only the id — the listener
 * ({@link SubscriptionPaymentNotifier}) re-reads the committed row.
 */
public record PaymentStatusChanged(UUID paymentId) {
}
