package com.travelplan.travel.service;

import com.travelplan.travel.dto.ManagerRankingEntry;
import com.travelplan.travel.dto.ManagerStatsResponse;
import com.travelplan.travel.repository.ManagerDestinationRatingView;
import com.travelplan.travel.repository.ManagerRatingView;
import com.travelplan.travel.repository.ManagerStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Manager statistics and provisional performance ranking built from what
 * travel-service owns (destinations, subscriptions, feedback) —
 * docs/lets-travel-architecture-decisions.md §5 addendum.
 *
 * Income (payment-service) and report counts (identity-service) are
 * deliberately not fetched here: no inter-service call is introduced by this
 * phase, and the final "performance score" the subject asks of the admin
 * dashboard is left to the dashboard/a later phase, which can combine this
 * endpoint's numbers with the other two services'. Until then the ranking's
 * score is provisional (rating, then number of feedbacks).
 *
 * Read-only: every method is a pure aggregation, nothing is written. Role
 * checks (any known role for a manager's stats, admin for the ranking) are
 * the controller's job.
 */
@Service
@Transactional(readOnly = true)
public class ManagerStatsService {

    /**
     * Best average first, managers without any feedback last, then the most
     * feedbacks first; the manager id only makes the order stable. Compared
     * on the exact ratio, not on the rounded value shown to clients.
     */
    private static final Comparator<ManagerRatingView> RANKING_ORDER = Comparator
            .comparing((ManagerRatingView m) -> m.feedbackCount() == 0)
            .thenComparing(Comparator.comparingDouble(
                    (ManagerRatingView m) -> m.feedbackCount() == 0 ? 0.0 : (double) m.ratingSum() / m.feedbackCount())
                    .reversed())
            .thenComparing(Comparator.comparingLong(ManagerRatingView::feedbackCount).reversed())
            .thenComparing(m -> m.managerId().toString());

    private final ManagerStatsRepository managerStatsRepository;

    public ManagerStatsService(ManagerStatsRepository managerStatsRepository) {
        this.managerStatsRepository = managerStatsRepository;
    }

    /**
     * Statistics for {@code managerId}. A manager id with no active
     * destination (unknown, or everything soft-deleted) yields zeros and an
     * empty list, not a 404: manager identity lives in identity-service, which
     * travel-service cannot query, so "unknown" and "nothing published" are
     * indistinguishable here.
     */
    public ManagerStatsResponse statsFor(UUID managerId) {
        LocalDate today = LocalDate.now();
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

    /** Every manager owning at least one active destination, best first — see {@link #RANKING_ORDER}. */
    public List<ManagerRankingEntry> ranking() {
        List<ManagerRatingView> sorted = managerStatsRepository.findAllManagerAggregates().stream()
                .sorted(RANKING_ORDER)
                .toList();
        List<ManagerRankingEntry> ranking = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ManagerRatingView m = sorted.get(i);
            ranking.add(new ManagerRankingEntry(
                    i + 1, m.managerId(), average(m.ratingSum(), m.feedbackCount()),
                    m.feedbackCount(), m.activeTravels()));
        }
        return ranking;
    }

    /** Average rounded to 2 decimals, {@code null} (not 0) when there is nothing to average. */
    private static Double average(long sum, long count) {
        if (count == 0) {
            return null;
        }
        return Math.round((double) sum / count * 100.0) / 100.0;
    }
}
