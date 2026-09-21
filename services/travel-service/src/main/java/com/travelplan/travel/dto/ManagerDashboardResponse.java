package com.travelplan.travel.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response of {@code GET /managers/me/dashboard}: the Travel Manager's key
 * statistics (subject: "income, number of trips, and number of travelers").
 *
 * <p>Everything except the money comes from travel-service's own graph. The
 * money ({@code income}, and each travel's {@code income}/{@code incomeAmount})
 * comes from payment-service; if it is unreachable those fields are {@code null}
 * and {@code partial} is {@code true} — the rest of the dashboard is still served.</p>
 *
 * @param managerId          whose dashboard this is
 * @param partial            {@code true} if income could not be fetched
 * @param trips              travels organised by the manager, by time status
 * @param travelers          distinct travelers with an {@code ACTIVE} subscription on those travels
 * @param rating             average rating and number of feedbacks over those travels
 * @param income             income overall, per currency, and for the last {@code months} months; {@code null} if unavailable
 * @param travels            one row per organised travel, newest start first (with its income)
 * @param recentFeedback     the newest feedbacks on those travels (at most 10)
 */
public record ManagerDashboardResponse(UUID managerId, boolean partial, Trips trips, long travelers,
                                       Rating rating, IncomeSummary income, List<TravelStatsRow> travels,
                                       List<FeedbackResponse> recentFeedback) {

    /**
     * @param organized every non-deleted travel of the manager ({@code past + ongoing + upcoming})
     * @param past      already ended
     * @param ongoing   started and not ended
     * @param upcoming  not started yet
     */
    public record Trips(long organized, long past, long ongoing, long upcoming) {
    }

    /** @param average raw mean over every feedback, {@code null} without feedback */
    public record Rating(Double average, long feedbackCount) {
    }
}
