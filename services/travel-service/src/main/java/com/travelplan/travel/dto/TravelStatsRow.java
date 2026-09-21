package com.travelplan.travel.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * One travel (destination) as a dashboard row: its dates, its status relative
 * to today, its ACTIVE subscribers, its feedback and, when payment-service
 * answered, what it brought in.
 *
 * @param status         {@code UPCOMING} (starts after today), {@code ONGOING} or {@code PAST} (ended before today)
 * @param capacity       seats, {@code null} if unlimited
 * @param subscribers    {@code ACTIVE} subscriptions
 * @param averageRating  raw mean rating, {@code null} without feedback
 * @param dampedRating   the mean pulled towards neutral when feedback is scarce
 *                       ({@link com.travelplan.travel.service.PerformanceScore#dampedRating}), used to rank travels
 * @param income         income per currency, {@code null} if payment-service was unavailable
 * @param incomeAmount   the reference-currency share of {@code income}, {@code null} if unavailable
 */
public record TravelStatsRow(UUID destinationId, UUID managerId, String name, String country,
                             LocalDate startDate, LocalDate endDate, String status, Integer capacity,
                             long subscribers, long feedbackCount, Double averageRating, double dampedRating,
                             Map<String, BigDecimal> income, BigDecimal incomeAmount) {
}
