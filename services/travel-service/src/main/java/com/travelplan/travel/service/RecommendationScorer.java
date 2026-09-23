package com.travelplan.travel.service;

import com.travelplan.travel.dto.RecommendationResponse;
import com.travelplan.travel.repository.RecommendationRow;
import com.travelplan.travel.repository.RecommendationRow.HistoryMatch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The scoring of the personalised recommendations
 * (docs/lets-travel-architecture-decisions.md §7) — <b>the</b> place to read
 * to defend the formula. Pure Java, no Spring, no I/O: it only turns the facts
 * {@link com.travelplan.travel.repository.RecommendationRepository} read from
 * the graph into a score and a list of human-readable reasons.
 *
 * <h3>The formula</h3>
 * <pre>
 *   score(C) = Σ over the traveler's history H  of  weight(H) × similarity(C, H)
 *
 *   similarity(C, H) = SAME_COUNTRY_POINTS              if C and H are in the same country
 *                    + SHARED_ACTIVITY_POINTS × min(number of activities in common, MAX_SHARED_ACTIVITIES)
 *                    + SHARED_ACCOMMODATION_TYPE_POINTS if they share an accommodation type
 *                    + SIMILAR_PRICE_POINTS             if their prices are within PRICE_TOLERANCE
 *
 *   weight(H) = FEEDBACK_WEIGHT[rating]                 if the traveler rated H   (an opinion beats a mere presence)
 *             = PARTICIPATION_WEIGHT                    otherwise, if they hold an ACTIVE subscription on H
 * </pre>
 * Four fields of the travel are used — country, activities, accommodation type
 * and price (the subject asks for at least three). The history weight is what
 * makes it personal: a 5 pulls destinations that look like H up, a 1 or 2
 * pushes them down (negative weight), participation without an opinion is a
 * mild vote for "more like this".
 *
 * <h3>Order and cold start</h3>
 * Highest score first; ties are broken by popularity (most {@code ACTIVE}
 * subscribers), then earliest {@code startDate}, then name, then id — so the
 * output is deterministic. A traveler with no history at all gets the cold-start
 * fallback: {@code score = number of ACTIVE subscribers}, same tie-breaks, and a
 * reason saying so. Candidates that resemble nothing in the history score 0 and
 * are ranked by popularity behind the ones that do (and ahead of the ones the
 * traveler has reasons to avoid).
 *
 * <p>Every reason carries the signed points it contributed, so the score is the
 * sum of the reasons — except when more than {@link #MAX_REASONS} apply, in which
 * case the smallest ones are folded into a last "and N smaller factors" line.</p>
 */
public final class RecommendationScorer {

    // ---- how much a history destination counts (the "personal" part) ----------------------

    /** ACTIVE subscription, no feedback (yet): a mild vote for destinations like it. */
    static final double PARTICIPATION_WEIGHT = 1.0;

    /**
     * Weight of a rated destination, by rating. 5 and 4 pull similar destinations up, 3 is
     * nearly neutral, 2 and 1 push them down. A 5 and a 1 weigh the same in absolute value (3);
     * a 2 pushes down a little less than a 4 pulls up (1.5 vs 2): disappointing, not a disaster.
     * Any rating outside 1..5 (impossible, the API rejects it) weighs 0.
     */
    static final Map<Integer, Double> FEEDBACK_WEIGHT = Map.of(
            5, 3.0,
            4, 2.0,
            3, 0.5,
            2, -1.5,
            1, -3.0);

    // ---- how much each comparable field of a travel counts (the "content" part) ----------

    /** Same country: the strongest signal (culture, climate, visa, language all follow). */
    static final double SAME_COUNTRY_POINTS = 3.0;

    /** Each activity name in common (case-insensitive), up to {@link #MAX_SHARED_ACTIVITIES}. */
    static final double SHARED_ACTIVITY_POINTS = 1.0;
    static final int MAX_SHARED_ACTIVITIES = 3;

    /** At least one accommodation type in common (hostel, hotel, ...), counted once. */
    static final double SHARED_ACCOMMODATION_TYPE_POINTS = 1.0;

    /**
     * Prices within 25 % of the history destination's price (two free trips also match): the
     * traveler's budget band, inferred from what they actually booked.
     */
    static final double SIMILAR_PRICE_POINTS = 1.0;
    static final double PRICE_TOLERANCE = 0.25;

    /** More reasons than this are truncated, see the class Javadoc. */
    static final int MAX_REASONS = 6;

    private RecommendationScorer() {
    }

    /**
     * Rank the candidates of {@code rows} and keep the {@code limit} best.
     *
     * @param rows what the graph query returned (one row per candidate × history pair,
     *             or one row per candidate with no history when the traveler has none)
     */
    public static List<RecommendationResponse> rank(List<RecommendationRow> rows, int limit) {
        Map<UUID, List<RecommendationRow>> byCandidate = new LinkedHashMap<>();
        for (RecommendationRow row : rows) {
            byCandidate.computeIfAbsent(row.destinationId(), id -> new ArrayList<>()).add(row);
        }
        boolean coldStart = rows.stream().noneMatch(row -> row.history() != null);

        List<Ranked> ranked = new ArrayList<>();
        for (List<RecommendationRow> candidateRows : byCandidate.values()) {
            ranked.add(coldStart ? coldStartScore(candidateRows.get(0)) : personalScore(candidateRows));
        }
        ranked.sort(Comparator
                .comparingDouble(Ranked::score).reversed()
                .thenComparing(Comparator.comparingLong((Ranked r) -> r.candidate().activeSubscribers()).reversed())
                .thenComparing(r -> r.candidate().startDate(), Comparator.nullsLast(LocalDate::compareTo))
                .thenComparing(r -> r.candidate().name())
                .thenComparing(r -> r.candidate().destinationId()));

        return ranked.stream()
                .limit(limit)
                .map(r -> new RecommendationResponse(
                        new RecommendationResponse.DestinationSummary(
                                r.candidate().destinationId(), r.candidate().name(), r.candidate().country(),
                                r.candidate().startDate(), r.candidate().endDate(), r.candidate().price()),
                        r.score(), r.reasons()))
                .toList();
    }

    /** Fallback when the traveler has no participation and no feedback: popularity. */
    private static Ranked coldStartScore(RecommendationRow candidate) {
        String reason = String.format(Locale.ROOT,
                "No history yet (no participation, no feedback): ranked by popularity, then soonest start"
                        + " (%d active subscriber%s)",
                candidate.activeSubscribers(), candidate.activeSubscribers() == 1 ? "" : "s");
        return new Ranked(candidate, round(candidate.activeSubscribers()), List.of(reason));
    }

    private static Ranked personalScore(List<RecommendationRow> candidateRows) {
        RecommendationRow candidate = candidateRows.get(0);
        List<Contribution> contributions = new ArrayList<>();
        for (RecommendationRow row : candidateRows) {
            contributions.addAll(contributionsFor(candidate, row.history()));
        }

        double score = round(contributions.stream().mapToDouble(Contribution::points).sum());
        return new Ranked(candidate, score, reasons(candidate, contributions));
    }

    /**
     * The contributions a single history match adds to {@code candidate}'s
     * score, or none if there is no history for this row or it carries no
     * weight (neither rated nor participated).
     */
    private static List<Contribution> contributionsFor(RecommendationRow candidate, HistoryMatch h) {
        if (h == null) {
            return List.of();
        }
        double weight = weight(h);
        if (weight == 0.0) {
            return List.of();
        }
        List<Contribution> contributions = new ArrayList<>();
        String basis = h.rating() != null ? "which you rated " + h.rating() : "which you took part in";
        String subject = "\"" + h.name() + "\", " + basis;
        if (h.sameCountry()) {
            contributions.add(new Contribution(weight * SAME_COUNTRY_POINTS,
                    "same country (" + candidate.country() + ") as " + subject));
        }
        int shared = Math.min(h.sharedActivities().size(), MAX_SHARED_ACTIVITIES);
        if (shared > 0) {
            contributions.add(new Contribution(weight * SHARED_ACTIVITY_POINTS * shared,
                    "shares " + (shared == 1 ? "activity " : "activities ")
                            + quoted(h.sharedActivities().subList(0, shared)) + " with " + subject));
        }
        if (!h.sharedAccommodationTypes().isEmpty()) {
            contributions.add(new Contribution(weight * SHARED_ACCOMMODATION_TYPE_POINTS,
                    "same accommodation type " + quoted(h.sharedAccommodationTypes()) + " as " + subject));
        }
        if (h.similarPrice()) {
            contributions.add(new Contribution(weight * SIMILAR_PRICE_POINTS,
                    "price " + money(candidate.price()) + " within " + Math.round(PRICE_TOLERANCE * 100)
                            + "% of " + money(h.price()) + " for " + subject));
        }
        return contributions;
    }

    /**
     * How much a history destination counts: the rating if there is one, else participation.
     * Neither rated nor participated cannot happen (the query only returns such destinations);
     * it would weigh 0.
     */
    private static double weight(HistoryMatch h) {
        if (h.rating() != null) {
            return FEEDBACK_WEIGHT.getOrDefault(h.rating(), 0.0);
        }
        return h.participated() ? PARTICIPATION_WEIGHT : 0.0;
    }

    private static List<String> reasons(RecommendationRow candidate, List<Contribution> contributions) {
        if (contributions.isEmpty()) {
            return List.of(String.format(Locale.ROOT,
                    "Nothing in common with your past trips or feedback: ranked by popularity (%d active subscriber%s)",
                    candidate.activeSubscribers(), candidate.activeSubscribers() == 1 ? "" : "s"));
        }
        List<Contribution> sorted = new ArrayList<>(contributions);
        sorted.sort(Comparator.comparingDouble((Contribution c) -> Math.abs(c.points())).reversed());

        List<String> reasons = new ArrayList<>();
        int shown = sorted.size() > MAX_REASONS ? MAX_REASONS - 1 : sorted.size();
        for (Contribution c : sorted.subList(0, shown)) {
            String text = c.text() + " (" + signed(c.points()) + ")";
            reasons.add(c.points() < 0 ? text + " - pulls this destination down" : text);
        }
        if (shown < sorted.size()) {
            List<Contribution> rest = sorted.subList(shown, sorted.size());
            reasons.add(rest.size() + " smaller factors (" + signed(rest.stream().mapToDouble(Contribution::points).sum()) + ")");
        }
        return reasons;
    }

    private static String quoted(List<String> values) {
        return values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));
    }

    private static String money(BigDecimal price) {
        return price == null ? "free" : price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String signed(double points) {
        BigDecimal value = BigDecimal.valueOf(points).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return (points >= 0 ? "+" : "") + value.toPlainString();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private record Contribution(double points, String text) {
    }

    private record Ranked(RecommendationRow candidate, double score, List<String> reasons) {
    }
}
