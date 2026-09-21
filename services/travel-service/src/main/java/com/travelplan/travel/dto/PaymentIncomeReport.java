package com.travelplan.travel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What payment-service answers to {@code GET /payments/income}: the income of
 * {@code COMPLETED}, travel-linked payments, one row per (travel, calendar
 * month, currency). Not an API type of travel-service — the dashboards compose
 * it with the graph (see {@link com.travelplan.travel.service.IncomeLedger}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentIncomeReport(List<Row> rows) {

    /**
     * @param travelId the Destination id
     * @param month    {@code YYYY-MM} of completion (UTC)
     * @param currency ISO currency code
     * @param total    sum of the completed payments
     * @param count    number of payments
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(UUID travelId, String month, String currency, BigDecimal total, long count) {
    }
}
