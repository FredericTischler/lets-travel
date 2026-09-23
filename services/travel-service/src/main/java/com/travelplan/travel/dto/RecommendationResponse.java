package com.travelplan.travel.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One line of {@code GET /travelers/me/recommendations}
 * (docs/lets-travel-architecture-decisions.md §7).
 *
 * {@code score} is the sum of the points listed in {@code reasons}: each
 * reason ends with its signed contribution, so a reader (or an auditor) can
 * recompute the score by hand. Positive points come from destinations the
 * traveler liked or took part in, negative ones from destinations they rated
 * low. When the traveler has no history at all the score is the number of
 * {@code ACTIVE} subscribers (popularity) and the single reason says so.
 *
 * <p>The destination's own identifying fields are grouped into
 * {@link DestinationSummary} to keep the constructor under Sonar's parameter
 * limit (java:S107); {@link JsonUnwrapped} flattens them back into the JSON
 * response, so {@code GET /travelers/me/recommendations} is unaffected — the
 * per-field getters below are kept (and {@link JsonIgnore}d) purely so
 * existing callers can keep reading {@code getName()}/{@code getScore()}/…
 * directly on a {@code RecommendationResponse}, as {@link
 * com.travelplan.travel.service.RecommendationScorer} and its tests do.</p>
 */
public class RecommendationResponse {

    /** The candidate destination's own fields, flattened into the parent JSON via {@link JsonUnwrapped}. */
    public record DestinationSummary(UUID destinationId, String name, String country, LocalDate startDate,
                                      LocalDate endDate, BigDecimal price) {
    }

    @JsonUnwrapped
    private final DestinationSummary destination;
    private final double score;
    private final List<String> reasons;

    public RecommendationResponse(DestinationSummary destination, double score, List<String> reasons) {
        this.destination = destination;
        this.score = score;
        this.reasons = reasons;
    }

    @JsonIgnore
    public UUID getDestinationId() {
        return destination.destinationId();
    }

    @JsonIgnore
    public String getName() {
        return destination.name();
    }

    @JsonIgnore
    public String getCountry() {
        return destination.country();
    }

    @JsonIgnore
    public LocalDate getStartDate() {
        return destination.startDate();
    }

    @JsonIgnore
    public LocalDate getEndDate() {
        return destination.endDate();
    }

    @JsonIgnore
    public BigDecimal getPrice() {
        return destination.price();
    }

    public double getScore() {
        return score;
    }

    public List<String> getReasons() {
        return reasons;
    }
}
