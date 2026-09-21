package com.travelplan.travel.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response of {@code GET /travelers/me/stats}: the traveler's personal
 * statistics (subject: "past travel participation, report counts,
 * subscription cancellations, and preferred payment methods").
 *
 * <p>Everything but the payment part comes from the graph. The payment part
 * ({@code preferredPaymentProvider}, {@code payments}) comes from
 * payment-service's {@code GET /payments/summary}; if it is unreachable both are
 * {@code null} and {@code partial} is {@code true}. The report count is
 * identity-service's ({@code GET /reports/count/{userId}}) and is added by the front.</p>
 *
 * @param travelerId                whose statistics these are
 * @param partial                   {@code true} if the payment summary could not be fetched
 * @param pastParticipationCount    {@code ACTIVE} subscriptions on travels that have ended
 * @param upcomingSubscriptionCount {@code ACTIVE} subscriptions on travels not ended yet
 * @param cancellationCount         subscriptions cancelled — by the traveler, by a manager/admin, or
 *                                  by a failed payment (the graph does not record why)
 * @param feedbackGivenCount        feedbacks the traveler gave
 * @param preferredPaymentProvider  most used provider over completed payments ({@code MANUAL}/{@code STRIPE}/{@code PAYPAL}),
 *                                  {@code null} if none or unavailable
 * @param payments                  completed payments per provider, {@code null} if unavailable
 * @param pastParticipations        the past participations, latest first
 */
public record TravelerStatsResponse(UUID travelerId, boolean partial, long pastParticipationCount,
                                    long upcomingSubscriptionCount, long cancellationCount,
                                    long feedbackGivenCount, String preferredPaymentProvider,
                                    PaymentSummaryReport payments, List<Participation> pastParticipations) {

    /** @param feedbackGiven whether the traveler already rated this travel */
    public record Participation(UUID destinationId, String name, String country, LocalDate startDate,
                                LocalDate endDate, boolean feedbackGiven) {
    }
}
