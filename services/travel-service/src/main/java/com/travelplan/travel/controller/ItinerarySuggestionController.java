package com.travelplan.travel.controller;

import com.travelplan.travel.dto.ItinerarySuggestionResponse;
import com.travelplan.travel.service.ItinerarySuggestionService;
import com.travelplan.travel.service.TokenValidationService;
import io.jsonwebtoken.Claims;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for itinerary suggestions
 * (docs/lets-travel-architecture-decisions.md §12): a chain of 2 to 3
 * eligible destinations connected by {@code TRANSPORT}, scored by the same
 * {@code RecommendationScorer} as {@code GET /travelers/me/recommendations}.
 * No business logic here — see {@link ItinerarySuggestionService}.
 */
@RestController
public class ItinerarySuggestionController {

    private final ItinerarySuggestionService itinerarySuggestionService;
    private final TokenValidationService tokenValidationService;

    public ItinerarySuggestionController(ItinerarySuggestionService itinerarySuggestionService,
                                          TokenValidationService tokenValidationService) {
        this.itinerarySuggestionService = itinerarySuggestionService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Up to 3 multi-stop itineraries the caller could still join, best first.
     * Any known role, self only — there is no {@code travelerId} parameter.
     *
     * @return 200 with the list (possibly empty),
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the token carries no recognized role
     */
    @GetMapping("/travelers/me/itinerary-suggestions")
    public ResponseEntity<List<ItinerarySuggestionResponse>> itinerarySuggestions(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRoleClaims(authorizationHeader);
        return ResponseEntity.ok(itinerarySuggestionService.suggest(tokenValidationService.callerId(claims)));
    }
}
