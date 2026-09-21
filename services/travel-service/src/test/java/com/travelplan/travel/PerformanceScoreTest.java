package com.travelplan.travel;

import com.travelplan.travel.service.PerformanceScore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure unit tests of the manager performance formula
 * ({@link PerformanceScore}, docs/lets-travel-architecture-decisions.md
 * "Dashboards" addendum) — no container: the formula is a function of numbers.
 */
class PerformanceScoreTest {

    @Test
    void weightsAreNormalisedToOne() {
        assertThat(PerformanceScore.WEIGHT_RATING + PerformanceScore.WEIGHT_INCOME
                + PerformanceScore.WEIGHT_TRAVELERS).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void oneFiveStarReviewNoLongerBeatsAHundredOfFourPointNine() {
        // The first ranking compared raw means: 5.0 (1 review) beat 4.9 (100 reviews).
        double single = PerformanceScore.dampedRating(5, 1);
        double established = PerformanceScore.dampedRating(490, 100);

        assertThat(single).isCloseTo(3.33, within(0.01));
        assertThat(established).isCloseTo(4.81, within(0.01));
        assertThat(established).isGreaterThan(single);
        assertThat(PerformanceScore.ratingScore(490, 100)).isGreaterThan(PerformanceScore.ratingScore(5, 1));
    }

    @Test
    void aManagerWithoutFeedbackIsNeutralNotPunished() {
        assertThat(PerformanceScore.dampedRating(0, 0)).isEqualTo(PerformanceScore.RATING_PRIOR);
        assertThat(PerformanceScore.ratingScore(0, 0)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void manyReviewsConvergeOnTheirRealAverage() {
        assertThat(PerformanceScore.dampedRating(5 * 10_000, 10_000)).isCloseTo(5.0, within(0.01));
    }

    @Test
    void scoreIsTheWeightedSumOnZeroToOneHundred() {
        assertThat(PerformanceScore.score(1.0, 1.0, 1.0)).isCloseTo(100.0, within(1e-9));
        assertThat(PerformanceScore.score(0.0, 0.0, 0.0)).isCloseTo(0.0, within(1e-9));
        // 100 * (0.5 * 0.5625 + 0.3 * 0.95 + 0.2 * 1.0)
        assertThat(PerformanceScore.score(0.5625, 0.95, 1.0)).isCloseTo(76.625, within(1e-9));
    }

    @Test
    void withoutIncomeTheRemainingWeightsAreRenormalised() {
        // Only rating (0.5) and travelers (0.2) count: 100 * (0.5 * 1 + 0.2 * 0) / 0.7
        assertThat(PerformanceScore.score(1.0, null, 0.0)).isCloseTo(100.0 * 0.5 / 0.7, within(1e-9));
        // A perfect manager stays at 100 even when income is unavailable.
        assertThat(PerformanceScore.score(1.0, null, 1.0)).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void relativeScoreIsZeroWhenNobodyHasAnything() {
        assertThat(PerformanceScore.relativeTo(0, 0)).isZero();
        assertThat(PerformanceScore.relativeTo(50, 200)).isCloseTo(0.25, within(1e-9));
    }
}
