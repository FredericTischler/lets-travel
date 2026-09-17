package com.travelplan.identity.repository;

import com.travelplan.identity.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Report}.
 *
 * All query methods filter on {@code deleted_at IS NULL} to honour the
 * soft-delete contract. No business logic lives here — only data access.
 */
public interface ReportRepository extends JpaRepository<Report, UUID> {

    /**
     * Find a non-deleted report by id.
     */
    @Query("SELECT r FROM Report r WHERE r.id = :id AND r.deletedAt IS NULL")
    Optional<Report> findActiveById(@Param("id") UUID id);

    /**
     * Return all non-deleted reports — backs the ADMIN-only review queue
     * ({@code GET /reports}).
     */
    @Query("SELECT r FROM Report r WHERE r.deletedAt IS NULL")
    List<Report> findAllActive();

    /**
     * Count non-deleted reports filed against a given user — backs
     * {@code GET /reports/count/{userId}} (a Traveler's personal stats page
     * and a Travel Manager's profile page both show this).
     */
    @Query("SELECT COUNT(r) FROM Report r WHERE r.reportedUserId = :reportedUserId AND r.deletedAt IS NULL")
    long countActiveByReportedUserId(@Param("reportedUserId") UUID reportedUserId);
}
