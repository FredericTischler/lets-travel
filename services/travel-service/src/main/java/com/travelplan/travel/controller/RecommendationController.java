package com.travelplan.travel.controller;

import com.travelplan.travel.dto.RecommendationResponse;
import com.travelplan.travel.exception.InsufficientRoleException;
import com.travelplan.travel.service.RecommendationService;
import com.travelplan.travel.service.TokenValidationService;
import io.jsonwebtoken.Claims;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the personalised recommendations
 * (docs/lets-travel-architecture-decisions.md §7). No business logic here —
 * see {@link RecommendationService} and {@link com.travelplan.travel.service.RecommendationScorer}.
 * Authorization is the manual first-line check through {@link TokenValidationService}.
 */
@RestController
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final TokenValidationService tokenValidationService;

    public RecommendationController(RecommendationService recommendationService,
                                    TokenValidationService tokenValidationService) {
        this.recommendationService = recommendationService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Destinations the caller could still join, ranked by how well they match their
     * participation and feedback history, each with a {@code score} and the
     * {@code reasons} behind it. Any known role, for the caller themselves; an
     * {@code ADMIN} may pass {@code travelerId} to see another traveler's list (support,
     * audit). {@code limit} defaults to 10 and is clamped to 1..50.
     *
     * @return 200 with the list (possibly empty),
     *         400 if {@code limit} or {@code travelerId} is malformed,
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the token carries no recognized role, or a non-admin asks for someone else
     */
    @GetMapping("/travelers/me/recommendations")
    public ResponseEntity<List<RecommendationResponse>> recommendations(
            @RequestParam(name = "travelerId", required = false) UUID travelerId,
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRoleClaims(authorizationHeader);
        UUID callerId = tokenValidationService.callerId(claims);
        UUID target = callerId;
        if (travelerId != null && !travelerId.equals(callerId)) {
            if (!tokenValidationService.isAdmin(claims)) {
                throw new InsufficientRoleException("Only an administrator may view another traveler's recommendations");
            }
            target = travelerId;
        }
        return ResponseEntity.ok(recommendationService.recommend(target, limit));
    }
}
