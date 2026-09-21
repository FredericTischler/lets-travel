package com.travelplan.travel.repository;

import java.util.UUID;

/**
 * One manager's aggregate over all their active destinations, as read by
 * {@link ManagerStatsRepository#findAllManagerAggregates()} — the input of
 * the admin ranking.
 */
public record ManagerRatingView(UUID managerId, long activeTravels, long feedbackCount, long ratingSum) {
}
