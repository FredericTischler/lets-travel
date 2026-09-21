package com.travelplan.travel.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Income block of the manager and admin dashboards, computed from
 * payment-service's {@code COMPLETED} payments (never from a subscription's
 * displayed price): what was actually collected.
 *
 * <p>Amounts are kept <b>per ISO currency</b> ({@code totals}) because summing
 * EUR and USD would be meaningless; the {@code *Amount} scalars are the
 * {@code referenceCurrency} share only (default {@code EUR}), convenient for a
 * chart or a score, with no conversion of the other currencies.</p>
 *
 * @param referenceCurrency currency the scalar {@code amount}/{@code windowAmount}/monthly {@code amount} are in
 * @param totals            lifetime income per currency
 * @param amount            lifetime income in {@code referenceCurrency}
 * @param months            size of the window ({@code byMonth}), current month included
 * @param windowTotals      income of the window per currency
 * @param windowAmount      income of the window in {@code referenceCurrency}
 * @param byMonth           one entry per month of the window, oldest first, months without income included
 */
public record IncomeSummary(String referenceCurrency, Map<String, BigDecimal> totals, BigDecimal amount,
                            int months, Map<String, BigDecimal> windowTotals, BigDecimal windowAmount,
                            List<MonthlyIncome> byMonth) {

    /**
     * @param month  {@code YYYY-MM}
     * @param totals income of the month per currency (empty if none)
     * @param amount income of the month in the reference currency (0 if none)
     */
    public record MonthlyIncome(String month, Map<String, BigDecimal> totals, BigDecimal amount) {
    }
}
