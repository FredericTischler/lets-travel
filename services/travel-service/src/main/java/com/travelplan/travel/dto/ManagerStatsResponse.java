package com.travelplan.travel.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * API response for {@code GET /managers/{managerId}/stats}: the building
 * blocks travel-service owns for a manager's public page and dashboards.
 *
 * Definitions (docs/lets-travel-architecture-decisions.md §5 addendum):
 * <ul>
 *   <li>{@code activeTravels} — the manager's destinations that are not
 *       soft-deleted ("active" in the repo-wide soft-delete sense, regardless
 *       of dates); {@code pastTravels} is the subset already ended.</li>
 *   <li>{@code subscribers} — distinct travelers holding an {@code ACTIVE}
 *       subscription on any of those destinations.</li>
 *   <li>{@code feedbackCount}/{@code averageRating} — over every feedback on
 *       those destinations; {@code averageRating} is {@code null} when there
 *       is no feedback yet (not 0, which would read as a bad score).</li>
 *   <li>{@code pastRatings} — one entry per past destination, with its own
 *       count/average.</li>
 * </ul>
 * Income and report counts are deliberately absent: they live in
 * payment-service / identity-service and are aggregated by the dashboard.
 */
public class ManagerStatsResponse {

    private final UUID managerId;
    private final long activeTravels;
    private final long pastTravels;
    private final long subscribers;
    private final long feedbackCount;
    private final Double averageRating;
    private final List<DestinationRating> pastRatings;

    public ManagerStatsResponse(UUID managerId, long activeTravels, long pastTravels, long subscribers,
                                 long feedbackCount, Double averageRating, List<DestinationRating> pastRatings) {
        this.managerId = managerId;
        this.activeTravels = activeTravels;
        this.pastTravels = pastTravels;
        this.subscribers = subscribers;
        this.feedbackCount = feedbackCount;
        this.averageRating = averageRating;
        this.pastRatings = pastRatings;
    }

    public UUID getManagerId() {
        return managerId;
    }

    public long getActiveTravels() {
        return activeTravels;
    }

    public long getPastTravels() {
        return pastTravels;
    }

    public long getSubscribers() {
        return subscribers;
    }

    public long getFeedbackCount() {
        return feedbackCount;
    }

    public Double getAverageRating() {
        return averageRating;
    }

    public List<DestinationRating> getPastRatings() {
        return pastRatings;
    }

    /** Rating summary of one past destination. {@code averageRating} is {@code null} without feedback. */
    public static class DestinationRating {

        private final UUID destinationId;
        private final String name;
        private final String country;
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final long feedbackCount;
        private final Double averageRating;

        public DestinationRating(UUID destinationId, String name, String country, LocalDate startDate,
                                  LocalDate endDate, long feedbackCount, Double averageRating) {
            this.destinationId = destinationId;
            this.name = name;
            this.country = country;
            this.startDate = startDate;
            this.endDate = endDate;
            this.feedbackCount = feedbackCount;
            this.averageRating = averageRating;
        }

        public UUID getDestinationId() {
            return destinationId;
        }

        public String getName() {
            return name;
        }

        public String getCountry() {
            return country;
        }

        public LocalDate getStartDate() {
            return startDate;
        }

        public LocalDate getEndDate() {
            return endDate;
        }

        public long getFeedbackCount() {
            return feedbackCount;
        }

        public Double getAverageRating() {
            return averageRating;
        }
    }
}
