package com.travelplan.travel.dto;

import jakarta.validation.constraints.Pattern;

/**
 * Optional body of {@code POST /destinations/{id}/subscriptions}
 * (docs/lets-travel-architecture-decisions.md §4 addendum).
 *
 * <p>Ignored for a free destination (price 0/null: the subscription is
 * {@code ACTIVE} immediately). For a paid destination, {@code provider} is
 * required — the traveler picks how to pay — and {@code currency} defaults to
 * {@code EUR} ({@code Destination} carries a price but no currency).</p>
 */
public class SubscribeRequest {

    private PaymentProvider provider;

    @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code")
    private String currency;

    public SubscribeRequest() {
        // required for Jackson deserialization
    }

    public PaymentProvider getProvider() {
        return provider;
    }

    public void setProvider(PaymentProvider provider) {
        this.provider = provider;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
