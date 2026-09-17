package com.travelplan.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code reports} table (V5__add_reports.sql).
 *
 * Schema is owned by Flyway. Hibernate ddl-auto is set to {@code validate}
 * only — this class must match the existing columns exactly.
 *
 * A report always targets a USER ({@link #reportedUserId}), whether that
 * user is a {@code TRAVEL_MANAGER} or a {@code TRAVELER} — see
 * docs/lets-travel-architecture-decisions.md §5. {@code status} is a plain
 * String on purpose (no Java enum, no exhaustive Postgres CHECK beyond the
 * known-values list): the transition rules between values are business
 * logic, enforced exclusively by
 * {@link com.travelplan.identity.service.ReportService} — same split as
 * payment-service's {@code Payment.status}.
 *
 * Soft-delete pattern: rows are never physically removed. The service sets
 * {@code deleted_at} to mark a report as inactive. Queries filtering active
 * reports always include {@code WHERE deleted_at IS NULL}.
 */
@Entity
@Table(name = "reports")
public class Report {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_REVIEWED = "REVIEWED";
    public static final String STATUS_DISMISSED = "DISMISSED";
    public static final String STATUS_ACTIONED = "ACTIONED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "reporter_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID reporterId;

    @Column(name = "reported_user_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID reportedUserId;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "deleted_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime deletedAt;

    protected Report() {
        // required by JPA
    }

    /**
     * A new report always starts {@link #STATUS_OPEN} — the client never
     * supplies a status at creation, mirroring {@code Payment}'s forced
     * PENDING at creation.
     */
    public Report(UUID reporterId, UUID reportedUserId, String reason) {
        this.reporterId = reporterId;
        this.reportedUserId = reportedUserId;
        this.reason = reason;
        this.status = STATUS_OPEN;
        this.createdAt = OffsetDateTime.now();
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

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
