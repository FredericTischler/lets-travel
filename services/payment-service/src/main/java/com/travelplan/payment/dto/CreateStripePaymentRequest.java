package com.travelplan.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for {@code POST /payments/stripe}.
 *
 * Validated by {@code @Valid} in the controller — same constraints as
 * {@link CreateManualPaymentRequest}, since a Stripe PaymentIntent is created
 * from exactly the same three inputs (owner, amount, currency); the Stripe
 * PaymentIntent id itself is produced server-side by
 * {@link com.travelplan.payment.service.StripePaymentService}, never supplied
 * by the client.
 */
public class CreateStripePaymentRequest extends SubscriptionLink {

    @NotNull(message = "must not be null")
    private UUID userId;

    @NotNull(message = "must not be null")
    @DecimalMin(value = "0.01", message = "must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "must not be blank")
    @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code")
    private String currency;

    public CreateStripePaymentRequest() {
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
