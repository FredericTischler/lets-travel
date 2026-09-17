package com.travelplan.identity.dto;

import com.travelplan.identity.entity.Report;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for a report resource.
 *
 * Intentionally omits {@code deleted_at}: that field is an internal
 * soft-delete implementation detail and must never be exposed over the API —
 * same rule as {@link UserResponse}.
 */
public class ReportResponse {

    private final UUID id;
    private final UUID reporterId;
    private final UUID reportedUserId;
    private final String reason;
    private final String status;
    private final OffsetDateTime createdAt;

    private ReportResponse(UUID id, UUID reporterId, UUID reportedUserId, String reason,
            String status, OffsetDateTime createdAt) {
        this.id = id;
        this.reporterId = reporterId;
        this.reportedUserId = reportedUserId;
        this.reason = reason;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static ReportResponse from(Report report) {
        return new ReportResponse(report.getId(), report.getReporterId(), report.getReportedUserId(),
                report.getReason(), report.getStatus(), report.getCreatedAt());
    }

    public UUID getId() {
        return id;
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public UUID getReportedUserId() {
        return reportedUserId;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
