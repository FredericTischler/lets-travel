package com.travelplan.travel.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of {@code POST /internal/subscriptions/{subscriptionRef}/payment-result},
 * sent by payment-service when a subscription-linked payment reaches a
 * terminal status (docs/lets-travel-architecture-decisions.md §4 addendum).
 * Carries everything travel-service needs to cross-check the payment against
 * the subscription it claims to settle, rather than trusting the reference alone.
 */
public class PaymentResultRequest {

    @NotNull(message = "must not be null")
    private UUID travelId;

    @NotNull(message = "must not be null")
    private UUID userId;

    @NotNull(message = "must not be null")
    private UUID paymentId;

    @NotBlank(message = "must not be blank")
    @Pattern(regexp = "^(COMPLETED|FAILED)$", message = "must be COMPLETED or FAILED")
    private String status;

    @NotNull(message = "must not be null")
    @DecimalMin(value = "0.00", message = "must not be negative")
    private BigDecimal amount;

    @NotBlank(message = "must not be blank")
    private String currency;

    public UUID getTravelId() {
        return travelId;
    }

    public void setTravelId(UUID travelId) {
        this.travelId = travelId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(UUID paymentId) {
        this.paymentId = paymentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
