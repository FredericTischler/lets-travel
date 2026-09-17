package com.travelplan.identity.controller;

import com.travelplan.identity.dto.CreateReportRequest;
import com.travelplan.identity.dto.ReportCountResponse;
import com.travelplan.identity.dto.ReportResponse;
import com.travelplan.identity.dto.UpdateReportStatusRequest;
import com.travelplan.identity.entity.User;
import com.travelplan.identity.service.AuthService;
import com.travelplan.identity.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the report (signalement) resource —
 * docs/lets-travel-architecture-decisions.md §5.
 *
 * No business logic here — data decisions are delegated to
 * {@link ReportService}, Bearer token validation to {@link AuthService}
 * (same manual mechanism every other controller in this service uses — no
 * Spring Security filter chain in this codebase). Exception-to-HTTP mapping
 * is handled by {@link com.travelplan.identity.exception.GlobalExceptionHandler}.
 *
 * <p>Every endpoint requires a valid Bearer token — there is no anonymous
 * use case here (unlike {@code POST /users}). {@code POST /reports} and
 * {@code GET /reports/count/{userId}} accept any of the 3 known roles
 * ({@link AuthService#requireAnyRole}: sujet §1 "Report Travel Managers or
 * other travelers" applies to every role, and a report count is not
 * sensitive — see {@link ReportService#countByReportedUserId} javadoc).
 * {@code GET /reports} and {@code PATCH /reports/{id}/status} stay
 * ADMIN-only ({@link AuthService#requireAdmin}) — sujet §1 "review reports
 * filed by travelers", no ownership nuance.</p>
 */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reportService;
    private final AuthService authService;

    public ReportController(ReportService reportService, AuthService authService) {
        this.reportService = reportService;
        this.authService = authService;
    }

    /**
     * File a new report against another user. Any authenticated role may
     * call this — see class-level note.
     *
     * @return 201 Created with the created report, 400 if the request body
     *         fails validation or the caller tries to report themselves, 404
     *         if {@code reportedUserId} does not correspond to an existing
     *         active user, 401 with a generic message if the Authorization
     *         header is missing/invalid/expired, 403 if the token is valid
     *         but does not carry a recognized role
     */
    @PostMapping
    public ResponseEntity<ReportResponse> create(
            @Valid @RequestBody CreateReportRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        User caller = authService.requireAnyRole(authorizationHeader);
        ReportResponse created = reportService.create(caller.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * List all active reports. Requires the caller to be an administrator —
     * see class-level note.
     *
     * @return 200 with the list (empty list if none), 401 with a generic
     *         message if the Authorization header is missing/invalid/expired,
     *         403 if the token is valid but does not carry the ADMIN role
     */
    @GetMapping
    public ResponseEntity<List<ReportResponse>> getAll(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        authService.requireAdmin(authorizationHeader);
        return ResponseEntity.ok(reportService.findAll());
    }

    /**
     * Transition a report's status to REVIEWED, DISMISSED or ACTIONED.
     * Requires the caller to be an administrator — see class-level note.
     *
     * @return 200 with the updated report, 400 if the target value is
     *         invalid, 409 if the report is already in a terminal status,
     *         404 if absent or soft-deleted, 401 with a generic message if
     *         the Authorization header is missing/invalid/expired, 403 if
     *         the token is valid but does not carry the ADMIN role
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<ReportResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateReportStatusRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        authService.requireAdmin(authorizationHeader);
        return ResponseEntity.ok(reportService.updateStatus(id, request));
    }

    /**
     * Count active reports filed against a given user. Any authenticated
     * role may call this — see class-level note and
     * {@link ReportService#countByReportedUserId} javadoc.
     *
     * @return 200 with the count (0 if none, including for an unknown
     *         userId — no existence check), 401 with a generic message if
     *         the Authorization header is missing/invalid/expired, 403 if
     *         the token is valid but does not carry a recognized role
     */
    @GetMapping("/count/{userId}")
    public ResponseEntity<ReportCountResponse> countByReportedUser(
            @PathVariable UUID userId,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        authService.requireAnyRole(authorizationHeader);
        return ResponseEntity.ok(new ReportCountResponse(reportService.countByReportedUserId(userId)));
    }
}
