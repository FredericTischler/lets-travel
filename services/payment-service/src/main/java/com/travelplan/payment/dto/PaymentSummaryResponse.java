package com.travelplan.payment.dto;

import com.travelplan.payment.entity.PaymentProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response of {@code GET /payments/summary}: a user's completed payments
 * grouped by provider — the payment half of the traveler's personal stats
 * page ("preferred payment methods", subject).
 *
 * @param userId             whose payments were summarized
 * @param totalCount         number of completed payments, all providers
 * @param byProvider         one entry per provider actually used, most-used first
 * @param mostUsedProvider   the first entry's provider, {@code null} if there is none
 */
public record PaymentSummaryResponse(UUID userId, long totalCount, List<ProviderSummary> byProvider,
                                      PaymentProvider mostUsedProvider) {

    /**
     * @param provider the payment provider
     * @param count    completed payments made with it
     * @param totals   sum of those payments per ISO currency code (never mixed across currencies)
     */
    public record ProviderSummary(PaymentProvider provider, long count, Map<String, BigDecimal> totals) {
    }
}
