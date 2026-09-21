package com.travelplan.travel.service;

/**
 * The manager performance score of {@code GET /managers/ranking} and the admin
 * dashboard (docs/lets-travel-architecture-decisions.md, "Dashboards" addendum):
 * a weighted sum of three components, each normalised to {@code [0, 1]}, scaled
 * to {@code [0, 100]}.
 *
 * <pre>
 *   score = 100 * (W_RATING * ratingScore + W_INCOME * incomeScore + W_TRAVELERS * travelersScore)
 *                / (sum of the weights of the components that are available)
 * </pre>
 *
 * <ul>
 *   <li><b>ratingScore</b> — the <i>damped</i> mean rating, rescaled from
 *       {@code [1, 5]}: {@code (sum + K * PRIOR) / (count + K)}. The prior pulls a
 *       manager with few reviews towards the neutral midpoint, so <b>one</b> 5-star
 *       review (damped 3.33) no longer beats a hundred 4.9 (damped 4.81) — the flaw
 *       of the first ranking, which compared raw means. A manager without any
 *       feedback sits at the prior (0.5), neither rewarded nor punished.</li>
 *   <li><b>incomeScore</b> — income in the reference currency divided by the best
 *       manager's (peer-relative, {@code 0} when nobody earned anything).</li>
 *   <li><b>travelersScore</b> — distinct travelers with an {@code ACTIVE}
 *       subscription, divided by the best manager's (the "other relevant metric":
 *       how many people the manager actually carries).</li>
 * </ul>
 *
 * When income is unavailable (payment-service down) it is left out and the two
 * remaining weights are renormalised: the ranking degrades instead of failing,
 * flagged {@code partial}.
 */
public final class PerformanceScore {

    /** Weight of the (damped) rating component. */
    public static final double WEIGHT_RATING = 0.5;
    /** Weight of the income component. */
    public static final double WEIGHT_INCOME = 0.3;
    /** Weight of the traveler-volume component. */
    public static final double WEIGHT_TRAVELERS = 0.2;

    /** Neutral rating a manager is pulled towards: the midpoint of the 1-5 scale. */
    public static final double RATING_PRIOR = 3.0;
    /** Number of virtual feedbacks at {@link #RATING_PRIOR} added to every manager/travel. */
    public static final double RATING_PRIOR_WEIGHT = 5.0;

    private static final double MIN_RATING = 1.0;
    private static final double MAX_RATING = 5.0;

    private PerformanceScore() {
    }

    /** Damped mean rating in {@code [1, 5]}; the prior alone (3.0) when there is no feedback. */
    public static double dampedRating(long ratingSum, long feedbackCount) {
        return (ratingSum + RATING_PRIOR_WEIGHT * RATING_PRIOR) / (feedbackCount + RATING_PRIOR_WEIGHT);
    }

    /** {@link #dampedRating} rescaled to {@code [0, 1]}. */
    public static double ratingScore(long ratingSum, long feedbackCount) {
        return (dampedRating(ratingSum, feedbackCount) - MIN_RATING) / (MAX_RATING - MIN_RATING);
    }

    /**
     * @param ratingScore    in {@code [0, 1]}
     * @param incomeScore    in {@code [0, 1]}, or {@code null} if income is unavailable
     * @param travelersScore in {@code [0, 1]}
     * @return the score in {@code [0, 100]}
     */
    public static double score(double ratingScore, Double incomeScore, double travelersScore) {
        double weighted = WEIGHT_RATING * ratingScore + WEIGHT_TRAVELERS * travelersScore;
        double weights = WEIGHT_RATING + WEIGHT_TRAVELERS;
        if (incomeScore != null) {
            weighted += WEIGHT_INCOME * incomeScore;
            weights += WEIGHT_INCOME;
        }
        return 100.0 * weighted / weights;
    }

    /** {@code value / max}, {@code 0} when {@code max} is not positive. */
    public static double relativeTo(double value, double max) {
        return max <= 0 ? 0.0 : value / max;
    }
}
