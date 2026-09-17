package com.travelplan.payment.dto;

import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for {@code POST /payments/paypal}.
 *
 * Extends the standard payment fields (see {@link PaymentResponse}) with
 * {@code approveUrl} — the PayPal-hosted URL the end user must be redirected
 * to in order to approve the order (standard PayPal Orders v2 flow, "rel":
 * "approve" link on the created order). May be {@code null} if PayPal's
 * response did not include an approve link (should not normally happen for a
 * freshly created order).
 */
public class PayPalPaymentResponse {

    private final UUID id;
    private final UUID userId;
    private final BigDecimal amount;
    private final String currency;
    private final String status;
    private final PaymentProvider provider;
    private final String orderId;
    private final String approveUrl;
    private final OffsetDateTime createdAt;

    private PayPalPaymentResponse(UUID id, UUID userId, BigDecimal amount, String currency, String status,
                                   PaymentProvider provider, String orderId, String approveUrl,
                                   OffsetDateTime createdAt) {
        this.id = id;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.provider = provider;
        this.orderId = orderId;
        this.approveUrl = approveUrl;
        this.createdAt = createdAt;
    }

    public static PayPalPaymentResponse from(Payment payment, String approveUrl) {
        return new PayPalPaymentResponse(
                payment.getId(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getProvider(),
                payment.getExternalReference(),
                approveUrl,
                payment.getCreatedAt());
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

    public PaymentProvider getProvider() {
        return provider;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getApproveUrl() {
        return approveUrl;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
