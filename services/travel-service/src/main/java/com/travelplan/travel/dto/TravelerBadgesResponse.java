package com.travelplan.travel.dto;

import java.util.List;

/**
 * API response for {@code GET /travelers/me/badges} (bonus feature,
 * docs/lets-travel-architecture-decisions.md §12): raw counts plus every
 * fixed badge tier, earned or not, with its threshold and current progress
 * — so the front can show a "3/5" style bar for an unearned one instead of
 * only a yes/no list. {@code code} is a stable identifier
 * ({@code EXPLORER}/{@code GLOBETROTTER}/{@code CRITIC}); the front owns the
 * label/icon shown for each, same as it already owns
 * {@code PAYMENT_PROVIDER_LABELS}.
 */
public class TravelerBadgesResponse {

    public record Badge(String code, int threshold, int progress, boolean earned) {
    }

    private final int destinationsVisited;
    private final int countriesVisited;
    private final int reviewsGiven;
    private final List<Badge> badges;

    public TravelerBadgesResponse(int destinationsVisited, int countriesVisited, int reviewsGiven, List<Badge> badges) {
        this.destinationsVisited = destinationsVisited;
        this.countriesVisited = countriesVisited;
        this.reviewsGiven = reviewsGiven;
        this.badges = badges;
    }

    public int getDestinationsVisited() {
        return destinationsVisited;
    }

    public int getCountriesVisited() {
        return countriesVisited;
    }

    public int getReviewsGiven() {
        return reviewsGiven;
    }

    public List<Badge> getBadges() {
        return badges;
    }
}
