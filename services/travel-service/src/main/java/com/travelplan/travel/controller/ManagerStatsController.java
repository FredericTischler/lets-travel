package com.travelplan.travel.controller;

import com.travelplan.travel.dto.ManagerRankingEntry;
import com.travelplan.travel.dto.ManagerStatsResponse;
import com.travelplan.travel.service.DashboardService;
import com.travelplan.travel.service.ManagerStatsService;
import com.travelplan.travel.service.TokenValidationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for manager statistics and the provisional performance
 * ranking (docs/lets-travel-architecture-decisions.md §5 addendum).
 *
 * No business logic here — see {@link ManagerStatsService}. Authorization is
 * the manual first-line check through {@link TokenValidationService}.
 */
@RestController
public class ManagerStatsController {

    private final ManagerStatsService managerStatsService;
    private final TokenValidationService tokenValidationService;
    private final DashboardService dashboardService;

    public ManagerStatsController(ManagerStatsService managerStatsService,
                                   TokenValidationService tokenValidationService,
                                   DashboardService dashboardService) {
        this.managerStatsService = managerStatsService;
        this.tokenValidationService = tokenValidationService;
        this.dashboardService = dashboardService;
    }

    /**
     * A manager's statistics: number of active travels, subscribers,
     * feedbacks, average rating and per-destination past ratings. Open to any
     * known role — the subject has Travelers view a manager's page
     * (statistics, past ratings) — so it exposes aggregates only, never an
     * individual feedback or traveler id. Income and report counts are not
     * included, see {@link ManagerStatsService}.
     *
     * @return 200 with the statistics (zeros if the id owns no active destination),
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the token carries no recognized role
     */
    @GetMapping("/managers/{managerId}/stats")
    public ResponseEntity<ManagerStatsResponse> stats(
            @PathVariable UUID managerId,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireAnyRole(authorizationHeader);
        return ResponseEntity.ok(managerStatsService.statsFor(managerId));
    }

    /**
     * Managers ordered by performance score: a weighted sum of a damped rating,
     * income (payment-service) and traveler volume — see
     * {@link com.travelplan.travel.service.PerformanceScore}. If payment-service
     * is unreachable the score is computed without income and each entry has
     * {@code partial: true}. Report counts are not included (identity-service's,
     * added by the front). {@code ADMIN} only.
     *
     * @return 200 with the ranking (empty if no manager owns an active destination),
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the caller is not an ADMIN
     */
    @GetMapping("/managers/ranking")
    public ResponseEntity<List<ManagerRankingEntry>> ranking(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireAdmin(authorizationHeader);
        return ResponseEntity.ok(dashboardService.ranking());
    }
}
