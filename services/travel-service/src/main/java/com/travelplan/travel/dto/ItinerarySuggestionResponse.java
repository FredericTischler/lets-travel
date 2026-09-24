package com.travelplan.travel.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/**
 * One line of {@code GET /travelers/me/itinerary-suggestions}
 * (docs/lets-travel-architecture-decisions.md §12): a chain of 2 to 3
 * destinations connected by active {@code TRANSPORT} edges, each already
 * eligible for the caller (same criteria as
 * {@code GET /travelers/me/recommendations}).
 *
 * {@code score} is the sum of every stop's own {@code RecommendationScorer}
 * score — no new scoring logic, only their aggregation across the chain.
 * {@code reasons} concatenates each stop's own reasons, prefixed by its name,
 * so the response stays auditable the same way a single recommendation is.
 */
public class ItinerarySuggestionResponse {

    public record Stop(@JsonUnwrapped RecommendationResponse.DestinationSummary destination, double score) {
    }

    private final List<Stop> stops;
    private final double score;
    private final int totalDurationMinutes;
    private final List<String> reasons;

    public ItinerarySuggestionResponse(List<Stop> stops, double score, int totalDurationMinutes, List<String> reasons) {
        this.stops = stops;
        this.score = score;
        this.totalDurationMinutes = totalDurationMinutes;
        this.reasons = reasons;
    }

    public List<Stop> getStops() {
        return stops;
    }

    public double getScore() {
        return score;
    }

    public int getTotalDurationMinutes() {
        return totalDurationMinutes;
    }

    public List<String> getReasons() {
        return reasons;
    }
}
