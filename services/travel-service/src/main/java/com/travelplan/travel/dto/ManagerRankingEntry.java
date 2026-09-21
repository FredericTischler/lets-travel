package com.travelplan.travel.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * One line of {@code GET /managers/ranking}, and of the top-manager lists of
 * the admin dashboard.
 *
 * <p>Ordered by {@code score}, then {@code feedbackCount}, then manager id (only
 * for a stable order) — see {@link com.travelplan.travel.service.PerformanceScore}
 * for the formula: a weighted sum of a <i>damped</i> rating (few reviews are
 * pulled towards neutral), income and traveler volume, in {@code [0, 100]}.
 * Report counts (identity-service) are not part of it; the dashboard front adds
 * them from {@code GET /reports/count/{userId}}.</p>
 *
 * <p>The first five fields are the original ones and keep their meaning
 * ({@code averageRating} is still the raw mean, {@code null} without feedback);
 * the rest were added. {@code rank} is the 1-based position in the list the
 * entry belongs to.</p>
 *
 * @param dampedRating    the rating actually scored, in {@code [1, 5]} (3.0 without feedback)
 * @param subscribers     distinct travelers with an {@code ACTIVE} subscription on the manager's travels
 * @param income          lifetime income per currency, {@code null} if payment-service was unavailable
 * @param incomeAmount    the reference-currency share of {@code income} (what the score used), {@code null} if unavailable
 * @param score           the performance score, {@code [0, 100]}, 2 decimals
 * @param partial         {@code true} if income was unavailable and the score was computed without it
 */
public record ManagerRankingEntry(int rank, UUID managerId, Double averageRating, long feedbackCount,
                                  long activeTravels, double dampedRating, long subscribers,
                                  Map<String, BigDecimal> income, BigDecimal incomeAmount, double score,
                                  boolean partial) {

    /** The same entry at another position (top lists re-rank a subset). */
    public ManagerRankingEntry withRank(int newRank) {
        return new ManagerRankingEntry(newRank, managerId, averageRating, feedbackCount, activeTravels,
                dampedRating, subscribers, income, incomeAmount, score, partial);
    }
}
