package com.travelplan.travel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * What payment-service answers to {@code GET /payments/summary}: a user's
 * {@code COMPLETED} payments per provider and the most used provider. Reused
 * as-is in the traveler statistics response (its shape is already the API shape
 * the front needs), so it is both the client-side and the outward DTO.
 *
 * @param totalCount       completed payments, all providers
 * @param byProvider       one entry per provider actually used, most used first
 * @param mostUsedProvider the preferred provider ({@code MANUAL}/{@code STRIPE}/{@code PAYPAL}), null if none
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentSummaryReport(long totalCount, List<ProviderUsage> byProvider, String mostUsedProvider) {

    /**
     * @param provider the payment provider name
     * @param count    completed payments made with it
     * @param totals   sum of those payments per ISO currency (never mixed)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderUsage(String provider, long count, Map<String, BigDecimal> totals) {
    }
}
