package com.travelplan.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mapped to the existing {@code payments} table.
 *
 * Schema is owned by Flyway (V1__init.sql). Hibernate ddl-auto is set to
 * {@code validate} — this class must match the existing columns exactly.
 *
 * {@code status} is a plain String on purpose (no Java enum, no Postgres
 * enum/CHECK constraint): the set of allowed values and the transition rules
 * between them are business logic, enforced exclusively by
 * {@link com.travelplan.payment.service.PaymentService}.
 *
 * Soft-delete pattern: rows are never physically removed. The service sets
 * {@code deleted_at} to mark a payment as inactive. Queries filtering active
 * payments always include {@code WHERE deleted_at IS NULL}.
 *
 * {@code userId} (V2__add_user_id.sql) is a plain UUID with no JPA
 * relationship/FK: payment-service has no access to identity-service's
 * database, so ownership is a logical reference only, never validated for
 * existence here.
 */
@Entity
@Table(name = "payments")
public class Payment {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "external_reference")
    private String externalReference;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "deleted_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime deletedAt;

    /**
     * {@code provider} (V3__add_provider.sql) distinguishes where a payment
     * originated. Stored as its enum name (STRING, not ORDINAL) so the column
     * stays readable/stable regardless of future enum reordering.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private PaymentProvider provider;

    protected Payment() {
        // required by JPA
    }

    /**
     * Manual payment (pre-existing path): always {@link PaymentProvider#MANUAL},
     * no external reference at creation time.
     */
    public Payment(UUID userId, BigDecimal amount, String currency) {
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.status = STATUS_PENDING;
        this.createdAt = OffsetDateTime.now();
        this.provider = PaymentProvider.MANUAL;
    }

    /**
     * Provider-backed payment (Stripe/PayPal): {@code externalReference}
     * carries the provider's own identifier for this payment — a Stripe
     * PaymentIntent id or a PayPal Order id — so the row can be reconciled
     * against the provider later (e.g. by a future webhook handler).
     */
    public Payment(UUID userId, BigDecimal amount, String currency, PaymentProvider provider,
                   String externalReference) {
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.status = STATUS_PENDING;
        this.createdAt = OffsetDateTime.now();
        this.provider = provider;
        this.externalReference = externalReference;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getExternalReference() {
        return externalReference;
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

    public PaymentProvider getProvider() {
        return provider;
    }
}