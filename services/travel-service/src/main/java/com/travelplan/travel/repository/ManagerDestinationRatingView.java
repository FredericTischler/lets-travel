package com.travelplan.travel.repository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One active destination of a manager together with its feedback aggregate,
 * as read by {@link ManagerStatsRepository}. {@code ratingSum} is exposed
 * (rather than only an average) so the caller can compute an exact overall
 * average weighted per feedback, not an average of per-destination averages.
 */
public record ManagerDestinationRatingView(UUID destinationId, String name, String country,
                                            LocalDate startDate, LocalDate endDate,
                                            long feedbackCount, long ratingSum) {
}
