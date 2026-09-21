package com.travelplan.payment.repository;

import com.travelplan.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Payment}.
 *
 * All query methods filter on {@code deleted_at IS NULL} to honour the
 * soft-delete contract. No business logic lives here — only data access.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Find a non-deleted payment by id.
     */
    @Query("SELECT p FROM Payment p WHERE p.id = :id AND p.deletedAt IS NULL")
    Optional<Payment> findActiveById(@Param("id") UUID id);

    /**
     * Return all non-deleted payments.
     */
    @Query("SELECT p FROM Payment p WHERE p.deletedAt IS NULL")
    List<Payment> findAllActive();

    /**
     * Return all non-deleted payments belonging to the given user.
     *
     * Backed by a partial index on (user_id) WHERE deleted_at IS NULL
     * (V2__add_user_id.sql), matching this exact query shape.
     */
    @Query("SELECT p FROM Payment p WHERE p.userId = :userId AND p.deletedAt IS NULL")
    List<Payment> findAllActiveByUserId(@Param("userId") UUID userId);

    /**
     * Find a non-deleted payment by its provider-side external reference
     * (a Stripe PaymentIntent id or a PayPal Order id — see
     * {@link Payment#getExternalReference()}).
     *
     * Used to reconcile a provider-originated payment with its own view of
     * the payment's state: the Stripe webhook handler and the PayPal capture
     * endpoint both look up the {@link Payment} row this way, since neither
     * provider knows this service's internal payment id.
     */
    @Query("SELECT p FROM Payment p WHERE p.externalReference = :externalReference AND p.deletedAt IS NULL")
    Optional<Payment> findActiveByExternalReference(@Param("externalReference") String externalReference);

    /**
     * Terminal ({@code COMPLETED}/{@code FAILED}) payments linked to a
     * subscription whose outcome travel-service has not acknowledged yet — the
     * reconciliation seam of docs/lets-travel-architecture-decisions.md §4
     * (backed by {@code idx_payments_pending_travel_notification}, V4).
     */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.subscriptionRef IS NOT NULL AND p.travelNotifiedAt IS NULL
              AND p.status <> 'PENDING' AND p.deletedAt IS NULL
            ORDER BY p.createdAt
            """)
    List<Payment> findTerminalAwaitingTravelNotification();

    /**
     * Record that travel-service acknowledged this payment's terminal status.
     * Its own transaction ({@code REQUIRES_NEW}): it is called from an
     * {@code AFTER_COMMIT} callback, where joining the (already committed)
     * outer transaction would silently drop the update.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE Payment p SET p.travelNotifiedAt = :at WHERE p.id = :id")
    int markTravelNotified(@Param("id") UUID id, @Param("at") OffsetDateTime at);

    /**
     * Per-provider, per-currency aggregate of a user's {@code COMPLETED}
     * payments — the raw rows behind {@code GET /payments/summary}. Each row
     * is {@code [PaymentProvider, String currency, Long count, BigDecimal total]}.
     */
    @Query("""
            SELECT p.provider, p.currency, COUNT(p), SUM(p.amount)
            FROM Payment p
            WHERE p.userId = :userId AND p.status = 'COMPLETED' AND p.deletedAt IS NULL
            GROUP BY p.provider, p.currency
            """)
    List<Object[]> summarizeCompletedByUser(@Param("userId") UUID userId);
}