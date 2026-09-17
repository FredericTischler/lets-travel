package com.travelplan.payment.repository;

import com.travelplan.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}