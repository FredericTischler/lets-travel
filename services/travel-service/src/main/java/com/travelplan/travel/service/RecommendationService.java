package com.travelplan.travel.service;

import com.travelplan.travel.dto.RecommendationResponse;
import com.travelplan.travel.repository.RecommendationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Personalised recommendations for a traveler
 * (docs/lets-travel-architecture-decisions.md §7): glue between the graph read
 * ({@link RecommendationRepository}) and the scoring ({@link RecommendationScorer}).
 * Read-only; who may ask for whom is the controller's decision.
 */
@Service
@Transactional(readOnly = true)
public class RecommendationService {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    private final RecommendationRepository recommendationRepository;

    public RecommendationService(RecommendationRepository recommendationRepository) {
        this.recommendationRepository = recommendationRepository;
    }

    /**
     * The best {@code limit} destinations {@code travelerId} could still join, best first.
     * {@code limit} is clamped into 1..{@link #MAX_LIMIT} rather than rejected. A traveler id the
     * graph has never seen simply has no history (cold start) — travel-service cannot tell an unknown
     * user from a new one (identity lives in identity-service), so there is no 404 here.
     */
    public List<RecommendationResponse> recommend(UUID travelerId, int limit) {
        int bounded = Math.clamp(limit, 1, MAX_LIMIT);
        return RecommendationScorer.rank(
                recommendationRepository.findCandidateRows(
                        travelerId, LocalDate.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC),
                        RecommendationScorer.PRICE_TOLERANCE),
                bounded);
    }
}
