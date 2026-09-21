package com.travelplan.travel.dto;

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
 */
public class RecommendationResponse {

    private final UUID destinationId;
    private final String name;
    private final String country;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final BigDecimal price;
    private final double score;
    private final List<String> reasons;

    public RecommendationResponse(UUID destinationId, String name, String country, LocalDate startDate,
                                   LocalDate endDate, BigDecimal price, double score, List<String> reasons) {
        this.destinationId = destinationId;
        this.name = name;
        this.country = country;
        this.startDate = startDate;
        this.endDate = endDate;
        this.price = price;
        this.score = score;
        this.reasons = reasons;
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

    public BigDecimal getPrice() {
        return price;
    }

    public double getScore() {
        return score;
    }

    public List<String> getReasons() {
        return reasons;
    }
}
