package com.travelplan.travel.service;

import com.travelplan.travel.dto.IncomeSummary;
import com.travelplan.travel.dto.PaymentIncomeReport;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * payment-service's income rows (per travel, month, currency) indexed so that
 * a dashboard can ask "how much did these travels bring in, overall and month
 * by month". It knows nothing about managers: which travels to sum is the
 * caller's decision, taken from the graph (only <b>active</b> destinations, so
 * a manager's per-travel figures always add up to their total).
 *
 * <p>Currencies are never merged: every total is a {@code currency -> amount}
 * map. Immutable once built.</p>
 */
public final class IncomeLedger {

    private static final Map<String, BigDecimal> NONE = Map.of();

    /** travelId -> month -> currency -> total. */
    private final Map<UUID, Map<YearMonth, Map<String, BigDecimal>>> byTravel = new HashMap<>();

    public IncomeLedger(PaymentIncomeReport report) {
        for (PaymentIncomeReport.Row row : report.rows()) {
            if (row.travelId() == null || row.month() == null || row.currency() == null || row.total() == null) {
                continue;
            }
            byTravel.computeIfAbsent(row.travelId(), k -> new HashMap<>())
                    .computeIfAbsent(YearMonth.parse(row.month()), k -> new TreeMap<>())
                    .merge(row.currency(), row.total(), BigDecimal::add);
        }
    }

    /** Lifetime income of one travel, per currency (empty if none). */
    public Map<String, BigDecimal> totalsFor(UUID travelId) {
        return totalsFor(List.of(travelId));
    }

    /** Lifetime income of a set of travels, per currency (empty if none). */
    public Map<String, BigDecimal> totalsFor(Collection<UUID> travelIds) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (UUID travelId : travelIds) {
            byTravel.getOrDefault(travelId, Map.of()).values()
                    .forEach(perCurrency -> perCurrency.forEach((c, v) -> totals.merge(c, v, BigDecimal::add)));
        }
        return totals;
    }

    /** The share of {@code totals} in {@code currency}, 0 if there is none. */
    public static BigDecimal amountIn(Map<String, BigDecimal> totals, String currency) {
        return totals.getOrDefault(currency, BigDecimal.ZERO);
    }

    /**
     * The income block for a set of travels: lifetime totals plus the
     * {@code months} calendar months ending at {@code lastMonth} (inclusive),
     * oldest first, empty months kept so a chart has no gaps.
     */
    public IncomeSummary summarize(Collection<UUID> travelIds, String referenceCurrency, int months,
                                   YearMonth lastMonth) {
        Map<YearMonth, Map<String, BigDecimal>> perMonth = new HashMap<>();
        for (UUID travelId : travelIds) {
            byTravel.getOrDefault(travelId, Map.of()).forEach((month, perCurrency) -> {
                Map<String, BigDecimal> bucket = perMonth.computeIfAbsent(month, k -> new TreeMap<>());
                perCurrency.forEach((c, v) -> bucket.merge(c, v, BigDecimal::add));
            });
        }
        Map<String, BigDecimal> lifetime = new TreeMap<>();
        perMonth.values().forEach(m -> m.forEach((c, v) -> lifetime.merge(c, v, BigDecimal::add)));

        Map<String, BigDecimal> window = new TreeMap<>();
        List<IncomeSummary.MonthlyIncome> byMonth = new ArrayList<>(months);
        for (int i = months - 1; i >= 0; i--) {
            YearMonth month = lastMonth.minusMonths(i);
            Map<String, BigDecimal> totals = perMonth.getOrDefault(month, NONE);
            totals.forEach((c, v) -> window.merge(c, v, BigDecimal::add));
            byMonth.add(new IncomeSummary.MonthlyIncome(month.toString(), Map.copyOf(totals),
                    amountIn(totals, referenceCurrency)));
        }
        return new IncomeSummary(referenceCurrency, lifetime, amountIn(lifetime, referenceCurrency), months,
                window, amountIn(window, referenceCurrency), byMonth);
    }
}
