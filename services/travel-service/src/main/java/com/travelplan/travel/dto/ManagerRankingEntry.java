package com.travelplan.travel.dto;

import java.util.UUID;

/**
 * One line of {@code GET /managers/ranking}.
 *
 * The order is by {@code averageRating} (descending, managers with no
 * feedback last) then {@code feedbackCount} (descending). That is a
 * provisional score: the subject also asks for income and other metrics,
 * which live in payment-service/identity-service and are not folded in here
 * (docs/lets-travel-architecture-decisions.md §5 addendum). {@code rank} is
 * the 1-based position in the list; managers tied on both criteria get
 * consecutive ranks (ties are broken by manager id, only for a stable order).
 */
public class ManagerRankingEntry {

    private final int rank;
    private final UUID managerId;
    private final Double averageRating;
    private final long feedbackCount;
    private final long activeTravels;

    public ManagerRankingEntry(int rank, UUID managerId, Double averageRating, long feedbackCount,
                                long activeTravels) {
        this.rank = rank;
        this.managerId = managerId;
        this.averageRating = averageRating;
        this.feedbackCount = feedbackCount;
        this.activeTravels = activeTravels;
    }

    public int getRank() {
        return rank;
    }

    public UUID getManagerId() {
        return managerId;
    }

    public Double getAverageRating() {
        return averageRating;
    }

    public long getFeedbackCount() {
        return feedbackCount;
    }

    public long getActiveTravels() {
        return activeTravels;
    }
}
