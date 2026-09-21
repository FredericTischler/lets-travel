package com.travelplan.travel.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One row of {@link RecommendationRepository#findCandidateRows}: an eligible
 * candidate destination, paired with <b>one</b> destination of the traveler's
 * history and the facts the graph established about the pair.
 *
 * <p>The graph answers "what do these two travels have in common?" (this
 * record); {@link com.travelplan.travel.service.RecommendationScorer} decides
 * how much each commonality is worth. Keeping the two apart means every weight
 * lives in Java, in one place, and the Cypher carries no magic number.</p>
 *
 * <p>{@code history} is {@code null} when the traveler has no history at all
 * (the candidate still comes back, exactly once, so the cold-start fallback can
 * rank it by popularity).</p>
 *
 * @param destinationId     candidate id
 * @param activeSubscribers number of {@code ACTIVE} subscriptions on the candidate (popularity)
 */
public record RecommendationRow(
        UUID destinationId,
        String name,
        String country,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal price,
        long activeSubscribers,
        HistoryMatch history) {

    /**
     * A destination of the traveler's history and how it compares to the candidate.
     *
     * @param rating                  the traveler's feedback rating (1..5), {@code null} if none
     * @param participated            the traveler holds an {@code ACTIVE} subscription on it
     * @param sameCountry             same country (case-insensitive)
     * @param sharedActivities        candidate activity names also offered by the history destination
     * @param sharedAccommodationTypes candidate accommodation types also offered by the history destination
     * @param similarPrice            price within the tolerance of the history destination's price
     */
    public record HistoryMatch(
            UUID id,
            String name,
            String country,
            BigDecimal price,
            Integer rating,
            boolean participated,
            boolean sameCountry,
            List<String> sharedActivities,
            List<String> sharedAccommodationTypes,
            boolean similarPrice) {
    }
}
