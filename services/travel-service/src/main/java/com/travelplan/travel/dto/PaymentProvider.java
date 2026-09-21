package com.travelplan.travel.dto;

/**
 * How a traveler pays for a subscription — the same three providers
 * payment-service supports ({@code PaymentProvider} there), each with its own
 * create endpoint. Chosen by the traveler in {@link SubscribeRequest}.
 */
public enum PaymentProvider {

    MANUAL("/payments"),
    STRIPE("/payments/stripe"),
    PAYPAL("/payments/paypal");

    private final String createPath;

    PaymentProvider(String createPath) {
        this.createPath = createPath;
    }

    /** payment-service path that creates a payment for this provider. */
    public String createPath() {
        return createPath;
    }
}
