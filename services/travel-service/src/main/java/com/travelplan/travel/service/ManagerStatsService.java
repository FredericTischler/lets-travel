package com.travelplan.travel.service;

import com.travelplan.travel.dto.ManagerRankingEntry;
import com.travelplan.travel.dto.ManagerStatsResponse;
import com.travelplan.travel.repository.DestinationSummaryView;
import com.travelplan.travel.repository.ManagerDestinationRatingView;
import com.travelplan.travel.repository.ManagerStatsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manager statistics and performance ranking built from what travel-service
 * owns (destinations, subscriptions, feedback) plus, for the ranking, the
 * income payment-service reports — docs/lets-travel-architecture-decisions.md
 * §5ter.5 and its "Dashboards" addendum.
 *
 * <p>This class does <b>no</b> inter-service call: the income is handed in as an
 * {@link IncomeLedger} (or {@code null} when payment-service could not answer) by
 * {@link DashboardService}, which is what keeps the calculation a pure function
 * of its inputs and lets a Neo4j read transaction never wait on the network.
 * Report counts (identity-service) are not part of the score; the front adds
 * them. The score itself is {@link PerformanceScore}.</p>
 *
 * Read-only. Role checks are the controller's job.
 */
@Service
@Transactional(readOnly = true)
public class ManagerStatsService {

    private final ManagerStatsRepository managerStatsRepository;
    private final String referenceCurrency;

    public ManagerStatsService(ManagerStatsRepository managerStatsRepository,
                               @Value("${dashboard.reference-currency}") String referenceCurrency) {
        this.managerStatsRepository = managerStatsRepository;
        this.referenceCurrency = referenceCurrency;
    }

    /**
     * Statistics for {@code managerId}. A manager id with no active
     * destination (unknown, or everything soft-deleted) yields zeros and an
     * empty list, not a 404: manager identity lives in identity-service, which
     * travel-service cannot query, so "unknown" and "nothing published" are
     * indistinguishable here.
     */
    public ManagerStatsResponse statsFor(UUID managerId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<ManagerDestinationRatingView> destinations = managerStatsRepository.findDestinationRatings(managerId);

        long feedbackCount = 0;
        long ratingSum = 0;
        long pastTravels = 0;
        List<ManagerStatsResponse.DestinationRating> pastRatings = new ArrayList<>();
        for (ManagerDestinationRatingView d : destinations) {
            feedbackCount += d.feedbackCount();
            ratingSum += d.ratingSum();
            if (d.endDate() != null && d.endDate().isBefore(today)) {
                pastTravels++;
                pastRatings.add(new ManagerStatsResponse.DestinationRating(
                        d.destinationId(), d.name(), d.country(), d.startDate(), d.endDate(),
                        d.feedbackCount(), average(d.ratingSum(), d.feedbackCount())));
            }
        }

        return new ManagerStatsResponse(
                managerId, destinations.size(), pastTravels,
                managerStatsRepository.countActiveSubscribers(managerId),
                feedbackCount, average(ratingSum, feedbackCount), pastRatings);
    }

    /** Ranking of every manager with an active destination — see {@link #rank}. */
    public List<ManagerRankingEntry> ranking(IncomeLedger ledger) {
        return rank(managerStatsRepository.findDestinationSummaries(null),
                managerStatsRepository.findActiveSubscribersByManager(), ledger);
    }

    /**
     * Rank the managers owning the given active destinations, best first
     * ({@link PerformanceScore}, then most feedbacks, then manager id for a
     * stable order). Compared on the exact score, not the rounded one shown.
     *
     * @param summaries           every active destination with a manager (all managers)
     * @param subscribersByManager distinct ACTIVE travelers per manager
     * @param ledger              payment-service's income, {@code null} if unavailable — the
     *                            score then leaves income out and every entry is {@code partial}
     */
    public List<ManagerRankingEntry> rank(List<DestinationSummaryView> summaries,
                                          Map<UUID, Long> subscribersByManager, IncomeLedger ledger) {
        Map<UUID, List<DestinationSummaryView>> byManager = new LinkedHashMap<>();
        for (DestinationSummaryView d : summaries) {
            byManager.computeIfAbsent(d.managerId(), k -> new ArrayList<>()).add(d);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<UUID, List<DestinationSummaryView>> e : byManager.entrySet()) {
            long feedbackCount = e.getValue().stream().mapToLong(DestinationSummaryView::feedbackCount).sum();
            long ratingSum = e.getValue().stream().mapToLong(DestinationSummaryView::ratingSum).sum();
            Map<String, BigDecimal> income = null;
            BigDecimal incomeAmount = null;
            if (ledger != null) {
                income = ledger.totalsFor(e.getValue().stream().map(DestinationSummaryView::destinationId).toList());
                incomeAmount = IncomeLedger.amountIn(income, referenceCurrency);
            }
            candidates.add(new Candidate(e.getKey(), e.getValue().size(), feedbackCount, ratingSum,
                    subscribersByManager.getOrDefault(e.getKey(), 0L), income, incomeAmount));
        }

        double maxIncome = candidates.stream().filter(c -> c.incomeAmount() != null)
                .mapToDouble(c -> c.incomeAmount().doubleValue()).max().orElse(0.0);
        double maxSubscribers = candidates.stream().mapToLong(Candidate::subscribers).max().orElse(0L);

        List<Scored> scored = candidates.stream().map(c -> {
            Double incomeScore = c.incomeAmount() == null
                    ? null : PerformanceScore.relativeTo(c.incomeAmount().doubleValue(), maxIncome);
            double score = PerformanceScore.score(
                    PerformanceScore.ratingScore(c.ratingSum(), c.feedbackCount()), incomeScore,
                    PerformanceScore.relativeTo(c.subscribers(), maxSubscribers));
            return new Scored(c, score);
        }).sorted(Comparator
                .comparingDouble(Scored::score).reversed()
                .thenComparing(Comparator.comparingLong((Scored s) -> s.candidate().feedbackCount()).reversed())
                .thenComparing(s -> s.candidate().managerId().toString()))
                .toList();

        List<ManagerRankingEntry> ranking = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            Candidate c = scored.get(i).candidate();
            ranking.add(new ManagerRankingEntry(
                    i + 1, c.managerId(), average(c.ratingSum(), c.feedbackCount()), c.feedbackCount(),
                    c.activeTravels(), round2(PerformanceScore.dampedRating(c.ratingSum(), c.feedbackCount())),
                    c.subscribers(), c.income(), c.incomeAmount(), round2(scored.get(i).score()),
                    ledger == null));
        }
        return ranking;
    }

    /** Average rounded to 2 decimals, {@code null} (not 0) when there is nothing to average. */
    static Double average(long sum, long count) {
        if (count == 0) {
            return null;
        }
        return round2((double) sum / count);
    }

    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Candidate(UUID managerId, long activeTravels, long feedbackCount, long ratingSum,
                             long subscribers, Map<String, BigDecimal> income, BigDecimal incomeAmount) {
    }

    private record Scored(Candidate candidate, double score) {
    }
}
