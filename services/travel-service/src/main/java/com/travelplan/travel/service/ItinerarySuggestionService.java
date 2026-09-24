package com.travelplan.travel.service;

import com.travelplan.travel.dto.ItinerarySuggestionResponse;
import com.travelplan.travel.dto.RecommendationResponse;
import com.travelplan.travel.repository.DestinationChain;
import com.travelplan.travel.repository.RecommendationRepository;
import com.travelplan.travel.repository.TransportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Itinerary suggestions (docs/lets-travel-architecture-decisions.md §12):
 * chains of 2 to 3 destinations, connected by active {@code TRANSPORT}
 * edges, that {@code travelerId} could still join — each stop already an
 * eligible candidate by {@link RecommendationRepository}'s own rules, scored
 * by the unmodified {@link RecommendationScorer}. Only the aggregation
 * across a chain (sum of each stop's score) is new logic.
 *
 * Read-only, no I/O beyond the two repositories it composes — same shape as
 * {@link RecommendationService}.
 */
@Service
@Transactional(readOnly = true)
public class ItinerarySuggestionService {

    static final int MAX_SUGGESTIONS = 3;

    private final RecommendationRepository recommendationRepository;
    private final TransportRepository transportRepository;

    public ItinerarySuggestionService(RecommendationRepository recommendationRepository,
                                       TransportRepository transportRepository) {
        this.recommendationRepository = recommendationRepository;
        this.transportRepository = transportRepository;
    }

    public List<ItinerarySuggestionResponse> suggest(UUID travelerId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        List<RecommendationResponse> eligible = RecommendationScorer.rank(
                recommendationRepository.findCandidateRows(travelerId, today, now, RecommendationScorer.PRICE_TOLERANCE),
                Integer.MAX_VALUE);
        Map<UUID, RecommendationResponse> byId = new LinkedHashMap<>();
        for (RecommendationResponse candidate : eligible) {
            byId.put(candidate.getDestinationId(), candidate);
        }
        if (byId.isEmpty()) {
            return List.of();
        }

        List<ItinerarySuggestionResponse> suggestions = new ArrayList<>();
        for (DestinationChain chain : transportRepository.findChains(byId.keySet())) {
            if (!chain.stopIds().stream().allMatch(byId::containsKey)) {
                continue; // every stop must itself be an eligible candidate, not only the origin
            }
            suggestions.add(toSuggestion(chain, byId));
        }

        suggestions.sort(Comparator
                .comparingDouble(ItinerarySuggestionResponse::getScore).reversed()
                .thenComparingInt(s -> s.getStops().size())
                .thenComparing(s -> s.getStops().get(0).destination().name()));
        return suggestions.stream().limit(MAX_SUGGESTIONS).toList();
    }

    private ItinerarySuggestionResponse toSuggestion(DestinationChain chain, Map<UUID, RecommendationResponse> byId) {
        List<ItinerarySuggestionResponse.Stop> stops = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        double total = 0.0;
        for (UUID stopId : chain.stopIds()) {
            RecommendationResponse candidate = byId.get(stopId);
            stops.add(new ItinerarySuggestionResponse.Stop(
                    new RecommendationResponse.DestinationSummary(
                            candidate.getDestinationId(), candidate.getName(), candidate.getCountry(),
                            candidate.getStartDate(), candidate.getEndDate(), candidate.getPrice()),
                    candidate.getScore()));
            total += candidate.getScore();
            for (String reason : candidate.getReasons()) {
                reasons.add(candidate.getName() + ": " + reason);
            }
        }
        return new ItinerarySuggestionResponse(stops, round(total), chain.totalDurationMinutes(), reasons);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
