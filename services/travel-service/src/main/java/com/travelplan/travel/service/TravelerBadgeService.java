package com.travelplan.travel.service;

import com.travelplan.travel.dto.TravelerBadgesResponse;
import com.travelplan.travel.repository.TravelerBadgeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Traveler badges (bonus feature, docs/lets-travel-architecture-decisions.md
 * §12): three fixed tiers computed from {@link TravelerBadgeRepository}'s
 * counts, never persisted — recalculated on every call, exactly like
 * {@link RecommendationScorer}'s score is never stored either.
 */
@Service
@Transactional(readOnly = true)
public class TravelerBadgeService {

    static final int EXPLORER_COUNTRIES = 1;
    static final int GLOBETROTTER_COUNTRIES = 5;
    static final int CRITIC_REVIEWS = 5;

    private final TravelerBadgeRepository travelerBadgeRepository;

    public TravelerBadgeService(TravelerBadgeRepository travelerBadgeRepository) {
        this.travelerBadgeRepository = travelerBadgeRepository;
    }

    public TravelerBadgesResponse badgesFor(UUID travelerId) {
        TravelerBadgeRepository.Counts counts =
                travelerBadgeRepository.findCounts(travelerId, LocalDate.now(ZoneOffset.UTC));
        List<TravelerBadgesResponse.Badge> badges = List.of(
                badge("EXPLORER", EXPLORER_COUNTRIES, counts.countriesVisited()),
                badge("GLOBETROTTER", GLOBETROTTER_COUNTRIES, counts.countriesVisited()),
                badge("CRITIC", CRITIC_REVIEWS, counts.reviewsGiven()));
        return new TravelerBadgesResponse(
                counts.destinationsVisited(), counts.countriesVisited(), counts.reviewsGiven(), badges);
    }

    private static TravelerBadgesResponse.Badge badge(String code, int threshold, int progress) {
        return new TravelerBadgesResponse.Badge(code, threshold, Math.min(progress, threshold), progress >= threshold);
    }
}
