package com.travelplan.travel.repository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One active destination with the counts the dashboards need, as returned by
 * {@link ManagerStatsRepository#findDestinationSummaries}: its {@code ACTIVE}
 * subscribers and its feedback aggregate. Not an API type.
 *
 * @param subscribers   {@code ACTIVE} subscriptions on this destination (one traveler = one active relation)
 * @param feedbackCount number of feedbacks
 * @param ratingSum     sum of their ratings (0 without feedback)
 */
public record DestinationSummaryView(UUID destinationId, UUID managerId, String name, String country,
                                     LocalDate startDate, LocalDate endDate, Integer capacity,
                                     long subscribers, long feedbackCount, long ratingSum) {
}
