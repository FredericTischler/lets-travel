package com.travelplan.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for {@code POST /payments}.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.payment.exception.GlobalExceptionHandler}
 * and returned as HTTP 400.
 *
 * Intentionally has NO {@code status} field: a manually-created payment is
 * always created as PENDING — {@link com.travelplan.payment.service.PaymentService}
 * forces this value and never reads a client-supplied status at creation time.
 */
public class CreateManualPaymentRequest {

    @NotNull(message = "must not be null")
    private UUID userId;

    @NotNull(message = "must not be null")
    @DecimalMin(value = "0.01", message = "must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "must not be blank")
    @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code")
    private String currency;

    public CreateManualPaymentRequest() {
        // required for Jackson deserialization
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
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