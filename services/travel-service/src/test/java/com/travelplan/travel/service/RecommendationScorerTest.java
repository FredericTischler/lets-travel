package com.travelplan.travel.service;

import com.travelplan.travel.dto.RecommendationResponse;
import com.travelplan.travel.repository.RecommendationRow;
import com.travelplan.travel.repository.RecommendationRow.HistoryMatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests of the scoring formula (no Spring, no database): the worked
 * example of docs/lets-travel-architecture-decisions.md §7 lives here, so that
 * the numbers quoted in the ADR are checked by a test. The graph side (which
 * facts the Cypher establishes) is covered by {@code RecommendationIntegrationTest}.
 */
class RecommendationScorerTest {

    private static final LocalDate START = LocalDate.of(2030, 6, 1);

    // History of the ADR's example traveler.
    private static final UUID LISBON = UUID.randomUUID();   // Portugal, 500, rated 5
    private static final UUID ALPS = UUID.randomUUID();     // Switzerland, 1200, rated 1

    private final UUID algarve = UUID.randomUUID();
    private final UUID zermatt = UUID.randomUUID();
    private final UUID porto = UUID.randomUUID();

    @Test
    void workedExampleFromTheAdr() {
        List<RecommendationRow> rows = new ArrayList<>();
        // Algarve Surf Week (Portugal, 480, surf, hostel): looks like Lisbon (rated 5), nothing like Alps.
        rows.add(row(algarve, "Algarve Surf Week", "Portugal", 480, 0, lisbon(true, List.of("surf"), List.of("hostel"), true)));
        rows.add(row(algarve, "Algarve Surf Week", "Portugal", 480, 0, alps(false, List.of(), List.of(), false)));
        // Zermatt Ski Weekend (Switzerland, 1100, ski, hotel): a clone of Alps (rated 1).
        rows.add(row(zermatt, "Zermatt Ski Weekend", "Switzerland", 1100, 0, lisbon(false, List.of(), List.of(), false)));
        rows.add(row(zermatt, "Zermatt Ski Weekend", "Switzerland", 1100, 0, alps(true, List.of("ski"), List.of("hotel"), true)));
        // Porto Wine Trip (Portugal, 700, wine, hotel): same country as Lisbon, same hotel type as Alps.
        rows.add(row(porto, "Porto Wine Trip", "Portugal", 700, 0, lisbon(true, List.of(), List.of(), false)));
        rows.add(row(porto, "Porto Wine Trip", "Portugal", 700, 0, alps(false, List.of(), List.of("hotel"), false)));

        List<RecommendationResponse> ranked = RecommendationScorer.rank(rows, 10);

        // Algarve: 5-star weight 3 x (3 country + 1 surf + 1 hostel + 1 price) = +18
        // Porto:   3 x 3 (country) = +9, and 1-star weight -3 x 1 (hotel) = -3  => 6
        // Zermatt: 1-star weight -3 x (3 + 1 ski + 1 hotel + 1 price)           => -18
        assertThat(ranked).extracting(RecommendationResponse::getName)
                .containsExactly("Algarve Surf Week", "Porto Wine Trip", "Zermatt Ski Weekend");
        assertThat(ranked).extracting(RecommendationResponse::getScore).containsExactly(18.0, 6.0, -18.0);

        assertThat(ranked.get(0).getReasons()).containsExactly(
                "same country (Portugal) as \"Lisbon Surf Camp\", which you rated 5 (+9)",
                "shares activity 'surf' with \"Lisbon Surf Camp\", which you rated 5 (+3)",
                "same accommodation type 'hostel' as \"Lisbon Surf Camp\", which you rated 5 (+3)",
                "price 480.00 within 25% of 500.00 for \"Lisbon Surf Camp\", which you rated 5 (+3)");
        assertThat(ranked.get(2).getReasons()).allSatisfy(r ->
                assertThat(r).contains("\"Alps Ski Week\", which you rated 1").endsWith("- pulls this destination down"));
    }

    @Test
    void participationWithoutFeedbackIsAMildPositiveVote() {
        RecommendationRow same = row(algarve, "Algarve", "Portugal", 480, 0,
                new HistoryMatch(LISBON, "Lisbon Surf Camp", "Portugal", price(500), null, true,
                        true, List.of(), List.of(), false));

        List<RecommendationResponse> ranked = RecommendationScorer.rank(List.of(same), 10);

        assertThat(ranked.get(0).getScore()).isEqualTo(RecommendationScorer.PARTICIPATION_WEIGHT * RecommendationScorer.SAME_COUNTRY_POINTS);
        assertThat(ranked.get(0).getReasons()).containsExactly(
                "same country (Portugal) as \"Lisbon Surf Camp\", which you took part in (+3)");
    }

    @Test
    void sharedActivitiesAreCapped() {
        RecommendationRow many = row(algarve, "Algarve", "Portugal", 480, 0,
                new HistoryMatch(LISBON, "Lisbon", "Spain", price(9000), 5, true,
                        false, List.of("a", "b", "c", "d", "e"), List.of(), false));

        List<RecommendationResponse> ranked = RecommendationScorer.rank(List.of(many), 10);

        assertThat(ranked.get(0).getScore()).isEqualTo(3.0 * RecommendationScorer.MAX_SHARED_ACTIVITIES);
        assertThat(ranked.get(0).getReasons()).singleElement().asString().contains("'a', 'b', 'c'").doesNotContain("'d'");
    }

    @Test
    void nothingInCommonScoresZeroAndSaysSo() {
        RecommendationRow unrelated = row(algarve, "Algarve", "Portugal", 480, 3,
                new HistoryMatch(ALPS, "Alps", "Switzerland", price(1200), 1, true,
                        false, List.of(), List.of(), false));

        RecommendationResponse only = RecommendationScorer.rank(List.of(unrelated), 10).get(0);

        assertThat(only.getScore()).isZero();
        assertThat(only.getReasons()).singleElement().asString()
                .startsWith("Nothing in common with your past trips").contains("3 active subscribers");
    }

    @Test
    void ties_areBrokenByPopularityThenStartDateThenName() {
        UUID popular = UUID.randomUUID();
        UUID soon = UUID.randomUUID();
        UUID lateA = UUID.randomUUID();
        UUID lateB = UUID.randomUUID();
        HistoryMatch nothing = new HistoryMatch(ALPS, "Alps", "Switzerland", price(1200), 4, true,
                false, List.of(), List.of(), false);
        List<RecommendationRow> rows = List.of(
                rowAt(lateB, "B trip", START.plusDays(20), 1, nothing),
                rowAt(lateA, "A trip", START.plusDays(20), 1, nothing),
                rowAt(soon, "Soon trip", START.plusDays(1), 1, nothing),
                rowAt(popular, "Popular trip", START.plusDays(90), 5, nothing));

        List<RecommendationResponse> ranked = RecommendationScorer.rank(rows, 10);

        assertThat(ranked).extracting(RecommendationResponse::getName)
                .containsExactly("Popular trip", "Soon trip", "A trip", "B trip");
    }

    @Test
    void coldStartRanksByActiveSubscribersThenSoonestStart() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<RecommendationRow> rows = List.of(
                rowAt(a, "Quiet but soon", START.plusDays(1), 0, null),
                rowAt(b, "Quiet and late", START.plusDays(50), 0, null),
                rowAt(c, "Popular", START.plusDays(100), 4, null));

        List<RecommendationResponse> ranked = RecommendationScorer.rank(rows, 10);

        assertThat(ranked).extracting(RecommendationResponse::getName)
                .containsExactly("Popular", "Quiet but soon", "Quiet and late");
        assertThat(ranked.get(0).getScore()).isEqualTo(4.0);
        assertThat(ranked).allSatisfy(r -> assertThat(r.getReasons()).singleElement().asString()
                .startsWith("No history yet"));
    }

    @Test
    void limitKeepsTheBest() {
        List<RecommendationRow> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(rowAt(UUID.randomUUID(), "Trip " + i, START.plusDays(i), i, null));
        }

        assertThat(RecommendationScorer.rank(rows, 2)).extracting(RecommendationResponse::getName)
                .containsExactly("Trip 4", "Trip 3");
    }

    @Test
    void manyReasonsAreFoldedIntoALastLineThatKeepsTheSum() {
        // Seven history destinations, all in the same country: 7 reasons -> 5 shown + 1 folded line.
        List<RecommendationRow> rows = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            rows.add(row(algarve, "Algarve", "Portugal", 480, 0,
                    new HistoryMatch(UUID.randomUUID(), "Trip " + i, "Portugal", price(9000), i <= 5 ? 6 - i : 5, true,
                            true, List.of(), List.of(), false)));
        }

        RecommendationResponse only = RecommendationScorer.rank(rows, 10).get(0);

        // weights: ratings 5,4,3,2,1,5,5 -> 3, 2, .5, -1.5, -3, 3, 3 = 7.0 x 3 = 21.0
        assertThat(only.getScore()).isEqualTo(21.0);
        assertThat(only.getReasons()).hasSize(RecommendationScorer.MAX_REASONS);
        assertThat(only.getReasons().get(RecommendationScorer.MAX_REASONS - 1)).startsWith("2 smaller factors (");
    }

    // ------------------------------------------------------------------ helpers

    private static HistoryMatch lisbon(boolean sameCountry, List<String> activities, List<String> types, boolean similarPrice) {
        return new HistoryMatch(LISBON, "Lisbon Surf Camp", "Portugal", price(500), 5, true,
                sameCountry, activities, types, similarPrice);
    }

    private static HistoryMatch alps(boolean sameCountry, List<String> activities, List<String> types, boolean similarPrice) {
        return new HistoryMatch(ALPS, "Alps Ski Week", "Switzerland", price(1200), 1, true,
                sameCountry, activities, types, similarPrice);
    }

    private static RecommendationRow row(UUID id, String name, String country, int price, long subscribers, HistoryMatch h) {
        return new RecommendationRow(id, name, country, START, START.plusDays(5), price(price), subscribers, h);
    }

    private static RecommendationRow rowAt(UUID id, String name, LocalDate start, long subscribers, HistoryMatch h) {
        return new RecommendationRow(id, name, "Nowhere", start, start.plusDays(5), price(100), subscribers, h);
    }

    private static BigDecimal price(int amount) {
        return BigDecimal.valueOf(amount);
    }
}
