package com.travelplan.travel.repository;

import java.util.List;
import java.util.UUID;

/**
 * A raw chain of destinations connected end to end by active
 * {@code TRANSPORT} edges, as read by {@link TransportRepository#findChains}
 * — structural only (docs/lets-travel-architecture-decisions.md §12).
 * Whether each stop is itself an eligible candidate (dates, capacity, not
 * already live for the traveler) is checked by
 * {@code com.travelplan.travel.service.ItinerarySuggestionService} against
 * {@code RecommendationRepository}'s candidate set, not here.
 */
public record DestinationChain(List<UUID> stopIds, int totalDurationMinutes) {
}
