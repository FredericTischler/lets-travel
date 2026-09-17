package com.travelplan.identity.service;

import com.travelplan.identity.dto.CreateReportRequest;
import com.travelplan.identity.dto.ReportResponse;
import com.travelplan.identity.dto.UpdateReportStatusRequest;
import com.travelplan.identity.entity.Report;
import com.travelplan.identity.exception.InvalidReportStatusValueException;
import com.travelplan.identity.exception.ReportAlreadyTerminalException;
import com.travelplan.identity.exception.ReportNotFoundException;
import com.travelplan.identity.exception.SelfReportException;
import com.travelplan.identity.exception.UserNotFoundException;
import com.travelplan.identity.repository.ReportRepository;
import com.travelplan.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for report (signalement) lifecycle management —
 * docs/lets-travel-architecture-decisions.md §5.
 *
 * Rules enforced here:
 * - A report is always created with status OPEN; the client cannot
 *   influence this at creation time (no status field on
 *   {@link CreateReportRequest}).
 * - {@code reporterId} is never trusted from the client — it is resolved by
 *   {@link com.travelplan.identity.controller.ReportController} from the
 *   caller's own Bearer token via {@link AuthService#requireAnyRole} and
 *   passed in here, same "server sets it from the token, not the body"
 *   principle already established for payment-service's ownership checks.
 * - A user cannot report themselves ({@code reporterId == reportedUserId}).
 * - {@code reportedUserId} must correspond to an existing active user in
 *   this service's own {@code users} table — see
 *   docs/lets-travel-architecture-decisions.md §5bis for why this differs
 *   from payment-service's unchecked {@code Payment.userId}.
 * - Status transitions only ever move OPEN -> REVIEWED, OPEN -> DISMISSED or
 *   OPEN -> ACTIONED. OPEN is never a valid transition target. Once a report
 *   is REVIEWED, DISMISSED or ACTIONED, its status is immutable — no further
 *   transition is permitted, even to the same value or to another terminal
 *   value. Mirrors payment-service's {@code PaymentService#updateStatus}.
 * - "Delete" always means soft-delete: deleted_at is set to now(), the row
 *   stays. Not exposed via any endpoint in this increment (no DELETE
 *   /reports/{id} was requested) but findAll/count still filter on it for
 *   forward compatibility.
 * - findAll / count silently filter out soft-deleted rows.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private static final Set<String> ALLOWED_TARGET_STATUSES = Set.of(
            Report.STATUS_REVIEWED, Report.STATUS_DISMISSED, Report.STATUS_ACTIONED);

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;

    public ReportService(ReportRepository reportRepository, UserRepository userRepository) {
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
    }

    /**
     * File a new report. Status is always forced to OPEN.
     *
     * @param reporterId the caller's own id, resolved from their token — never client-supplied
     * @throws SelfReportException if {@code request.getReportedUserId()} equals {@code reporterId}
     * @throws UserNotFoundException if {@code request.getReportedUserId()} does not
     *         correspond to an existing active user (docs/lets-travel-architecture-decisions.md §5bis)
     */
    @Transactional
    public ReportResponse create(UUID reporterId, CreateReportRequest request) {
        UUID reportedUserId = request.getReportedUserId();

        if (reporterId.equals(reportedUserId)) {
            throw new SelfReportException();
        }

        userRepository.findActiveById(reportedUserId)
                .orElseThrow(() -> new UserNotFoundException(reportedUserId));

        Report report = new Report(reporterId, reportedUserId, request.getReason());
        Report saved = reportRepository.save(report);
        return ReportResponse.from(saved);
    }

    /**
     * Return all active reports. ADMIN-only — enforced by
     * {@link com.travelplan.identity.controller.ReportController}, not here
     * (no ownership nuance for this endpoint, see
     * docs/lets-travel-architecture-decisions.md §5).
     */
    public List<ReportResponse> findAll() {
        return reportRepository.findAllActive().stream()
                .map(ReportResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Transition a report's status to REVIEWED, DISMISSED or ACTIONED.
     *
     * @throws ReportNotFoundException            if the report does not exist or is soft-deleted
     * @throws InvalidReportStatusValueException  if the requested target value is not one of the 3 terminal values
     * @throws ReportAlreadyTerminalException     if the report's current status is already terminal
     */
    @Transactional
    public ReportResponse updateStatus(UUID id, UpdateReportStatusRequest request) {
        Report report = reportRepository.findActiveById(id)
                .orElseThrow(() -> new ReportNotFoundException(id));

        if (!ALLOWED_TARGET_STATUSES.contains(request.getStatus())) {
            throw new InvalidReportStatusValueException(request.getStatus());
        }

        if (ALLOWED_TARGET_STATUSES.contains(report.getStatus())) {
            throw new ReportAlreadyTerminalException(id, report.getStatus());
        }

        report.setStatus(request.getStatus());
        // the dirty check within the transaction persists the change automatically
        return ReportResponse.from(report);
    }

    /**
     * Count active reports filed against a given user.
     *
     * <p>Open to any authenticated role at the controller level: it only
     * returns a number, never report contents (reason/status/reporter
     * identity), and the subject explicitly requires both a Traveler's own
     * stats page and a Travel Manager's public-ish profile page to surface
     * this count — restricting it to the resource owner would break the
     * latter use case (a Traveler viewing a Travel Manager's report count is
     * not the Travel Manager). No existence check on {@code userId}: an
     * unknown or already-soft-deleted user simply yields 0, not a 404 — this
     * mirrors {@code PaymentService#deleteAllByUserId}'s "empty result is
     * not an error" rule.</p>
     */
    public long countByReportedUserId(UUID userId) {
        return reportRepository.countActiveByReportedUserId(userId);
    }
}
