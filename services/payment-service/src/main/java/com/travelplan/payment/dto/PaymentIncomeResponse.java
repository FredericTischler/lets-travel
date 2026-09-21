package com.travelplan.payment.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Response of {@code GET /payments/income}: the income of {@code COMPLETED},
 * travel-linked payments, one row per (travel, calendar month, currency).
 *
 * @param rows the aggregate rows, ordered by month then travel; empty if there is no such payment
 */
public record PaymentIncomeResponse(List<Row> rows) {

    /**
     * @param travelId the Destination id the payments were made for
     * @param month    calendar month of completion, UTC, {@code YYYY-MM}
     * @param currency ISO currency code — totals are never mixed across currencies
     * @param total    sum of the completed payments' amounts
     * @param count    number of those payments
     */
    public record Row(UUID travelId, String month, String currency, BigDecimal total, long count) {
    }
}
