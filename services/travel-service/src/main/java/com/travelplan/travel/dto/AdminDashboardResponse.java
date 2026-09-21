package com.travelplan.travel.dto;

import java.util.List;

/**
 * Response of {@code GET /admin/dashboard} (ADMIN only): the platform view the
 * subject asks of the Admin — top-ranking managers and travels, income for the
 * last months, number of organised travels, the detailed travel history and
 * feedbacks to assess satisfaction.
 *
 * <p>As for the manager dashboard, money comes from payment-service: if it is
 * unreachable {@code income} and every per-travel/per-manager income are
 * {@code null}, {@code partial} is {@code true}, the by-income lists are empty,
 * and the manager scores are computed without the income component (see
 * {@link com.travelplan.travel.service.PerformanceScore}). Report counts are
 * identity-service's and are added by the front.</p>
 *
 * @param partial              {@code true} if income could not be fetched
 * @param referenceCurrency    currency the scalar amounts (and the income score) are in
 * @param months               window of {@code income.byMonth}
 * @param totals               platform-wide counts
 * @param income               platform income (of active travels), lifetime and per month; {@code null} if unavailable
 * @param topManagersByScore   best managers by the performance score (at most 5), re-ranked 1..n
 * @param topManagersByRating  best managers by damped rating, only those with feedback (at most 5)
 * @param topManagersByIncome  best managers by income in the reference currency, only those above 0 (at most 5)
 * @param topTravelsByIncome   best travels by income (at most 5), only those above 0
 * @param topTravelsByRating   best travels by damped rating, only those with feedback (at most 5)
 * @param travelHistory        every past travel, latest end first, with subscribers, income and rating
 * @param recentFeedback       the newest feedbacks on the platform (at most 20); the full list is {@code GET /feedback}
 */
public record AdminDashboardResponse(boolean partial, String referenceCurrency, int months, Totals totals,
                                     IncomeSummary income, List<ManagerRankingEntry> topManagersByScore,
                                     List<ManagerRankingEntry> topManagersByRating,
                                     List<ManagerRankingEntry> topManagersByIncome,
                                     List<TravelStatsRow> topTravelsByIncome,
                                     List<TravelStatsRow> topTravelsByRating,
                                     List<TravelStatsRow> travelHistory,
                                     List<FeedbackResponse> recentFeedback) {

    /**
     * @param managers         managers owning at least one active travel
     * @param organizedTravels every non-deleted travel ({@code past + ongoing + upcoming})
     * @param activeTravelers  distinct travelers with an {@code ACTIVE} subscription
     * @param feedbackCount    feedbacks on active travels
     * @param averageRating    raw mean over them, {@code null} without feedback
     */
    public record Totals(long managers, long organizedTravels, long pastTravels, long ongoingTravels,
                         long upcomingTravels, long activeTravelers, long feedbackCount, Double averageRating) {
    }
}
