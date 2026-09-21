package com.travelplan.travel.controller;

import com.travelplan.travel.dto.AdminDashboardResponse;
import com.travelplan.travel.dto.ManagerDashboardResponse;
import com.travelplan.travel.service.DashboardService;
import com.travelplan.travel.service.TokenValidationService;
import io.jsonwebtoken.Claims;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for the Travel Manager and Admin dashboards
 * (docs/lets-travel-architecture-decisions.md, "Dashboards" addendum).
 *
 * No business logic here — see {@link DashboardService}. Authorization is the
 * manual first-line check through {@link TokenValidationService}.
 */
@RestController
public class DashboardController {

    private final DashboardService dashboardService;
    private final TokenValidationService tokenValidationService;

    public DashboardController(DashboardService dashboardService, TokenValidationService tokenValidationService) {
        this.dashboardService = dashboardService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * The Travel Manager's own dashboard: income (overall, per travel, last
     * {@code months} months), number of trips, number of travelers, average
     * rating, recent feedback. A {@code TRAVEL_MANAGER} always sees their own;
     * an {@code ADMIN} may look at any manager with {@code managerId} (and at
     * their own travels without it). If payment-service is down, income is
     * {@code null} and {@code partial} is {@code true}.
     *
     * @param managerId whose dashboard; defaults to the caller. A TRAVEL_MANAGER passing another id gets 403
     * @param months    income window, default 6, kept within 1..24
     * @return 200 with the dashboard,
     *         401 if the Authorization header is missing/invalid/expired,
     *         403 for a TRAVELER, or a manager asking for someone else's dashboard
     */
    @GetMapping("/managers/me/dashboard")
    public ResponseEntity<ManagerDashboardResponse> managerDashboard(
            @RequestParam(name = "managerId", required = false) UUID managerId,
            @RequestParam(name = "months", defaultValue = "" + DashboardService.DEFAULT_MONTHS) int months,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireManagerOrAdmin(authorizationHeader);
        UUID target = managerId != null ? managerId : tokenValidationService.callerId(claims);
        tokenValidationService.requireOwnerOrAdmin(claims, target);
        return ResponseEntity.ok(dashboardService.managerDashboard(target, months));
    }

    /**
     * The platform dashboard: top managers and travels, income for the last
     * months, number of organised travels, past-travel history, recent
     * feedback. {@code ADMIN} only.
     *
     * @param months income window, default 6, kept within 1..24
     * @return 200 with the dashboard,
     *         401 if the Authorization header is missing/invalid/expired,
     *         403 if the caller is not an ADMIN
     */
    @GetMapping("/admin/dashboard")
    public ResponseEntity<AdminDashboardResponse> adminDashboard(
            @RequestParam(name = "months", defaultValue = "" + DashboardService.DEFAULT_MONTHS) int months,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireAdmin(authorizationHeader);
        return ResponseEntity.ok(dashboardService.adminDashboard(months));
    }
}
