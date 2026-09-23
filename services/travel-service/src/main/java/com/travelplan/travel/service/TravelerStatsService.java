package com.travelplan.travel.service;

import com.travelplan.travel.dto.PaymentSummaryReport;
import com.travelplan.travel.dto.TravelerStatsResponse;
import com.travelplan.travel.repository.FeedbackRepository;
import com.travelplan.travel.repository.TravelerStatsRepository;
import com.travelplan.travel.repository.TravelerStatsRepository.TravelerSubscriptionSummary;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The traveler's personal statistics (docs/lets-travel-architecture-decisions.md,
 * "Dashboards" addendum): past participations, subscription cancellations,
 * feedbacks given — all from the graph — and the preferred payment provider,
 * which payment-service owns ({@link PaymentStatsClient}, the caller's own token
 * forwarded). If payment-service cannot answer, the payment fields are
 * {@code null} and the response is flagged {@code partial}.
 *
 * <p>Definitions: a <b>participation</b> is an {@code ACTIVE} subscription on a
 * travel that has already ended (the same notion as feedback eligibility, ADR
 * §5ter.1); a <b>cancellation</b> is a {@code CANCELLED} relation, whoever
 * cancelled it and whatever the reason (a failed payment also ends in
 * {@code CANCELLED}, the graph does not keep the cause — ADR §4 décision D).
 * No {@code @Transactional}: the payment call is network I/O.</p>
 */
@Service
public class TravelerStatsService {

    private static final String ACTIVE = "ACTIVE";
    private static final String CANCELLED = "CANCELLED";

    private final TravelerStatsRepository travelerStatsRepository;
    private final FeedbackRepository feedbackRepository;
    private final PaymentStatsClient paymentStatsClient;

    public TravelerStatsService(TravelerStatsRepository travelerStatsRepository,
                                FeedbackRepository feedbackRepository, PaymentStatsClient paymentStatsClient) {
        this.travelerStatsRepository = travelerStatsRepository;
        this.feedbackRepository = feedbackRepository;
        this.paymentStatsClient = paymentStatsClient;
    }

    /**
     * @param authorizationHeader the caller's own header, forwarded to payment-service, which enforces
     *                            "own summary unless admin" again on its side
     */
    public TravelerStatsResponse statsFor(UUID travelerId, String authorizationHeader) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<TravelerSubscriptionSummary> subscriptions = travelerStatsRepository.findSubscriptions(travelerId);

        List<TravelerStatsResponse.Participation> past = new ArrayList<>();
        long upcoming = 0;
        long cancellations = 0;
        for (TravelerSubscriptionSummary s : subscriptions) {
            if (ACTIVE.equals(s.status())) {
                if (s.endDate() != null && s.endDate().isBefore(today)) {
                    past.add(new TravelerStatsResponse.Participation(
                            s.destinationId(), s.name(), s.country(), s.startDate(), s.endDate(), s.feedbackGiven()));
                } else {
                    upcoming++;
                }
            } else if (CANCELLED.equals(s.status())) {
                cancellations++;
            }
        }

        Optional<PaymentSummaryReport> payments = paymentStatsClient.fetchSummary(authorizationHeader, travelerId);
        return new TravelerStatsResponse(
                travelerId, payments.isEmpty(), past.size(), upcoming, cancellations,
                feedbackRepository.findForTraveler(travelerId).size(),
                payments.map(PaymentSummaryReport::mostUsedProvider).orElse(null),
                payments.orElse(null), past);
    }
}
