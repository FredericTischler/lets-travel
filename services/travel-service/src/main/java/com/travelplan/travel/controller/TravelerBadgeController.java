package com.travelplan.travel.controller;

import com.travelplan.travel.dto.TravelerBadgesResponse;
import com.travelplan.travel.service.TokenValidationService;
import com.travelplan.travel.service.TravelerBadgeService;
import io.jsonwebtoken.Claims;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for traveler badges (bonus feature,
 * docs/lets-travel-architecture-decisions.md §12). No business logic here —
 * see {@link TravelerBadgeService}.
 */
@RestController
public class TravelerBadgeController {

    private final TravelerBadgeService travelerBadgeService;
    private final TokenValidationService tokenValidationService;

    public TravelerBadgeController(TravelerBadgeService travelerBadgeService,
                                    TokenValidationService tokenValidationService) {
        this.travelerBadgeService = travelerBadgeService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * The caller's own badge progress (countries/destinations visited,
     * reviews given, and every fixed tier with its threshold and current
     * progress). Any known role, self only.
     *
     * @return 200 with the badges,
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the token carries no recognized role
     */
    @GetMapping("/travelers/me/badges")
    public ResponseEntity<TravelerBadgesResponse> myBadges(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireAnyRoleClaims(authorizationHeader);
        return ResponseEntity.ok(travelerBadgeService.badgesFor(tokenValidationService.callerId(claims)));
    }
}
