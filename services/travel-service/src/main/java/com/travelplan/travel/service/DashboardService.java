package com.travelplan.travel.service;

import com.travelplan.travel.dto.AdminDashboardResponse;
import com.travelplan.travel.dto.FeedbackResponse;
import com.travelplan.travel.dto.IncomeSummary;
import com.travelplan.travel.dto.ManagerDashboardResponse;
import com.travelplan.travel.dto.ManagerRankingEntry;
import com.travelplan.travel.dto.TravelStatsRow;
import com.travelplan.travel.repository.DestinationSummaryView;
import com.travelplan.travel.repository.FeedbackRepository;
import com.travelplan.travel.repository.ManagerStatsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Composes the Travel Manager and Admin dashboards and the manager ranking
 * (docs/lets-travel-architecture-decisions.md, "Dashboards" addendum): the
 * graph counts of travel-service ({@link ManagerStatsRepository},
 * {@link FeedbackRepository}, {@link ManagerStatsService}) on one side, the
 * income payment-service reports ({@link PaymentStatsClient}) on the other.
 *
 * <p><b>Degradation, not failure:</b> if payment-service cannot answer, income
 * fields are {@code null}, {@code partial} is {@code true}, and everything else
 * is still served (the manager ranking then scores without income). No
 * {@code @Transactional} here on purpose: the payment call is network I/O and
 * must not hold a database transaction open; each repository read is its own
 * short one.</p>
 *
 * Role/ownership checks are the controller's job.
 */
@Service
public class DashboardService {

    /** Size of every "top" list of the admin dashboard. */
    static final int TOP_N = 5;
    static final int RECENT_FEEDBACK_MANAGER = 10;
    static final int RECENT_FEEDBACK_ADMIN = 20;
    public static final int DEFAULT_MONTHS = 6;
    public static final int MAX_MONTHS = 24;

    static final String STATUS_PAST = "PAST";
    static final String STATUS_ONGOING = "ONGOING";
    static final String STATUS_UPCOMING = "UPCOMING";

    private final ManagerStatsRepository managerStatsRepository;
    private final FeedbackRepository feedbackRepository;
    private final ManagerStatsService managerStatsService;
    private final PaymentStatsClient paymentStatsClient;
    private final String referenceCurrency;

    public DashboardService(ManagerStatsRepository managerStatsRepository, FeedbackRepository feedbackRepository,
                            ManagerStatsService managerStatsService, PaymentStatsClient paymentStatsClient,
                            @Value("${dashboard.reference-currency}") String referenceCurrency) {
        this.managerStatsRepository = managerStatsRepository;
        this.feedbackRepository = feedbackRepository;
        this.managerStatsService = managerStatsService;
        this.paymentStatsClient = paymentStatsClient;
        this.referenceCurrency = referenceCurrency;
    }

    /** {@code GET /managers/ranking}: every manager scored with the income payment-service reports, if it answers. */
    public List<ManagerRankingEntry> ranking() {
        return managerStatsService.ranking(fetchLedger());
    }

    /**
     * The dashboard of {@code managerId}: trips, travelers, rating, income
     * (overall, per currency, over the last {@code months} months, per travel)
     * and the newest feedbacks. A manager id with no active travel yields zeros
     * and empty lists (identity-service owns "does this manager exist").
     */
    public ManagerDashboardResponse managerDashboard(UUID managerId, int months) {
        LocalDate today = LocalDate.now();
        IncomeLedger ledger = fetchLedger();
        List<DestinationSummaryView> summaries = managerStatsRepository.findDestinationSummaries(managerId);
        List<TravelStatsRow> travels = summaries.stream().map(d -> toRow(d, ledger, today)).toList();

        long feedbackCount = summaries.stream().mapToLong(DestinationSummaryView::feedbackCount).sum();
        long ratingSum = summaries.stream().mapToLong(DestinationSummaryView::ratingSum).sum();
        IncomeSummary income = ledger == null ? null : ledger.summarize(
                summaries.stream().map(DestinationSummaryView::destinationId).toList(),
                referenceCurrency, clampMonths(months), currentMonth());

        return new ManagerDashboardResponse(
                managerId, ledger == null,
                new ManagerDashboardResponse.Trips(travels.size(), count(travels, STATUS_PAST),
                        count(travels, STATUS_ONGOING), count(travels, STATUS_UPCOMING)),
                managerStatsRepository.countActiveSubscribers(managerId),
                new ManagerDashboardResponse.Rating(ManagerStatsService.average(ratingSum, feedbackCount), feedbackCount),
                income, travels,
                feedbackRepository.findRecent(managerId, RECENT_FEEDBACK_MANAGER).stream()
                        .map(FeedbackResponse::from).toList());
    }

    /**
     * The platform dashboard: top managers (by score, rating, income) and top
     * travels (by income, rating), income of the last {@code months} months, the
     * counts of organised travels, the past-travel history and the newest feedbacks.
     */
    public AdminDashboardResponse adminDashboard(int months) {
        LocalDate today = LocalDate.now();
        IncomeLedger ledger = fetchLedger();
        boolean partial = ledger == null;
        List<DestinationSummaryView> summaries = managerStatsRepository.findDestinationSummaries(null);
        List<TravelStatsRow> travels = summaries.stream().map(d -> toRow(d, ledger, today)).toList();
        List<ManagerRankingEntry> ranking = managerStatsService.rank(
                summaries, managerStatsRepository.findActiveSubscribersByManager(), ledger);

        long feedbackCount = summaries.stream().mapToLong(DestinationSummaryView::feedbackCount).sum();
        long ratingSum = summaries.stream().mapToLong(DestinationSummaryView::ratingSum).sum();
        AdminDashboardResponse.Totals totals = new AdminDashboardResponse.Totals(
                ranking.size(), travels.size(), count(travels, STATUS_PAST), count(travels, STATUS_ONGOING),
                count(travels, STATUS_UPCOMING), managerStatsRepository.countAllActiveSubscribers(),
                feedbackCount, ManagerStatsService.average(ratingSum, feedbackCount));

        int window = clampMonths(months);
        IncomeSummary income = partial ? null : ledger.summarize(
                summaries.stream().map(DestinationSummaryView::destinationId).toList(),
                referenceCurrency, window, currentMonth());

        List<ManagerRankingEntry> topByScore = rerank(ranking.stream().limit(TOP_N).toList());
        List<ManagerRankingEntry> topByRating = rerank(ranking.stream()
                .filter(m -> m.feedbackCount() > 0)
                .sorted(Comparator.comparingDouble(ManagerRankingEntry::dampedRating).reversed()
                        .thenComparing(Comparator.comparingLong(ManagerRankingEntry::feedbackCount).reversed())
                        .thenComparing(m -> m.managerId().toString()))
                .limit(TOP_N).toList());
        List<ManagerRankingEntry> topByIncome = partial ? List.of() : rerank(ranking.stream()
                .filter(m -> m.incomeAmount().signum() > 0)
                .sorted(Comparator.comparing(ManagerRankingEntry::incomeAmount).reversed()
                        .thenComparing(m -> m.managerId().toString()))
                .limit(TOP_N).toList());

        List<TravelStatsRow> topTravelsByIncome = partial ? List.of() : travels.stream()
                .filter(t -> t.incomeAmount().signum() > 0)
                .sorted(Comparator.comparing(TravelStatsRow::incomeAmount).reversed()
                        .thenComparing(t -> t.destinationId().toString()))
                .limit(TOP_N).toList();
        List<TravelStatsRow> topTravelsByRating = travels.stream()
                .filter(t -> t.feedbackCount() > 0)
                .sorted(Comparator.comparingDouble(TravelStatsRow::dampedRating).reversed()
                        .thenComparing(Comparator.comparingLong(TravelStatsRow::feedbackCount).reversed())
                        .thenComparing(t -> t.destinationId().toString()))
                .limit(TOP_N).toList();
        List<TravelStatsRow> history = travels.stream()
                .filter(t -> STATUS_PAST.equals(t.status()))
                .sorted(Comparator.comparing(TravelStatsRow::endDate).reversed()
                        .thenComparing(t -> t.destinationId().toString()))
                .toList();

        return new AdminDashboardResponse(
                partial, referenceCurrency, window, totals, income, topByScore, topByRating, topByIncome,
                topTravelsByIncome, topTravelsByRating, history,
                feedbackRepository.findRecent(null, RECENT_FEEDBACK_ADMIN).stream()
                        .map(FeedbackResponse::from).toList());
    }

    /** {@code months} kept within {@code [1, MAX_MONTHS]}: a chart window, not something worth a 400. */
    public static int clampMonths(int months) {
        return Math.max(1, Math.min(MAX_MONTHS, months));
    }

    private IncomeLedger fetchLedger() {
        Optional<IncomeLedger> ledger = paymentStatsClient.fetchIncome().map(IncomeLedger::new);
        return ledger.orElse(null);
    }

    /** The calendar month "now" is in, UTC — the same bucketing payment-service uses. */
    private static YearMonth currentMonth() {
        return YearMonth.now(ZoneOffset.UTC);
    }

    private TravelStatsRow toRow(DestinationSummaryView d, IncomeLedger ledger, LocalDate today) {
        Map<String, BigDecimal> income = ledger == null ? null : ledger.totalsFor(d.destinationId());
        return new TravelStatsRow(
                d.destinationId(), d.managerId(), d.name(), d.country(), d.startDate(), d.endDate(),
                statusOf(d, today), d.capacity(), d.subscribers(), d.feedbackCount(),
                ManagerStatsService.average(d.ratingSum(), d.feedbackCount()),
                ManagerStatsService.round2(PerformanceScore.dampedRating(d.ratingSum(), d.feedbackCount())),
                income, income == null ? null : IncomeLedger.amountIn(income, referenceCurrency));
    }

    private static String statusOf(DestinationSummaryView d, LocalDate today) {
        if (d.endDate() != null && d.endDate().isBefore(today)) {
            return STATUS_PAST;
        }
        if (d.startDate() == null || d.startDate().isAfter(today)) {
            return STATUS_UPCOMING;
        }
        return STATUS_ONGOING;
    }

    private static long count(List<TravelStatsRow> travels, String status) {
        return travels.stream().filter(t -> status.equals(t.status())).count();
    }

    /** Positions 1..n in the order given (a top list ranks its own subset). */
    private static List<ManagerRankingEntry> rerank(List<ManagerRankingEntry> entries) {
        return java.util.stream.IntStream.range(0, entries.size())
                .mapToObj(i -> entries.get(i).withRank(i + 1))
                .toList();
    }
}
