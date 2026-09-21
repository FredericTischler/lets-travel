package com.travelplan.travel.controller;

import com.travelplan.travel.dto.TravelerStatsResponse;
import com.travelplan.travel.service.TokenValidationService;
import com.travelplan.travel.service.TravelerStatsService;
import io.jsonwebtoken.Claims;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for the traveler's personal statistics
 * (docs/lets-travel-architecture-decisions.md, "Dashboards" addendum). No
 * business logic here — see {@link TravelerStatsService}.
 */
@RestController
public class TravelerStatsController {

    private final TravelerStatsService travelerStatsService;
    private final TokenValidationService tokenValidationService;

    public TravelerStatsController(TravelerStatsService travelerStatsService,
                                   TokenValidationService tokenValidationService) {
        this.travelerStatsService = travelerStatsService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * The caller's own statistics: past participations, subscription
     * cancellations, feedbacks given, preferred payment provider. Any known role,
     * self only — {@code travelerId} is accepted only from an {@code ADMIN}. If
     * payment-service is down the payment fields are {@code null} and
     * {@code partial} is {@code true}. The traveler's report count is
     * identity-service's ({@code GET /reports/count/{userId}}).
     *
     * @param travelerId whose statistics; defaults to the caller
     * @return 200 with the statistics,
     *         401 if the Authorization header is missing/invalid/expired,
     *         403 if the token has no recognised role, or a non-admin asks for another user
     */
    @GetMapping("/travelers/me/stats")
    public ResponseEntity<TravelerStatsResponse> stats(
            @RequestParam(name = "travelerId", required = false) UUID travelerId,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRoleClaims(authorizationHeader);
        UUID target = travelerId != null ? travelerId : tokenValidationService.callerId(claims);
        tokenValidationService.requireSelfOrAdmin(claims, target);
        return ResponseEntity.ok(travelerStatsService.statsFor(target, authorizationHeader));
    }
}
