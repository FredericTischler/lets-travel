package com.travelplan.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared owner/amount/currency fields and validation rules for every
 * "create a payment" request (manual, Stripe, PayPal) — the three concrete
 * DTOs ({@link CreateManualPaymentRequest}, {@link CreateStripePaymentRequest},
 * {@link CreatePayPalPaymentRequest}) never differ on these three inputs,
 * only on what a given provider needs beyond them (nothing extra today).
 * Extracted here purely to remove duplicated getter/setter boilerplate
 * across the three — same public getter/setter names as before, so the
 * JSON request shape each subclass accepts is unchanged.
 *
 * <p>Extends {@link SubscriptionLink} so the optional travel/subscription
 * link stays available on all three, exactly as before this class existed.</p>
 */
public abstract class CreatePaymentRequest extends SubscriptionLink {

    @NotNull(message = "must not be null")
    private UUID userId;

    @NotNull(message = "must not be null")
    @DecimalMin(value = "0.01", message = "must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "must not be blank")
    @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code")
    private String currency;

    protected CreatePaymentRequest() {
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
