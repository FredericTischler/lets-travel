package com.travelplan.payment.dto;

import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for {@code POST /payments/paypal}.
 *
 * Extends the standard payment fields (see {@link BasePaymentResponse}/
 * {@link PaymentResponse}) with {@code approveUrl} — the PayPal-hosted URL
 * the end user must be redirected to in order to approve the order
 * (standard PayPal Orders v2 flow, "rel": "approve" link on the created
 * order). May be {@code null} if PayPal's response did not include an
 * approve link (should not normally happen for a freshly created order).
 */
public class PayPalPaymentResponse extends BasePaymentResponse {

    private final String orderId;
    private final String approveUrl;

    private PayPalPaymentResponse(UUID id, UUID userId, BigDecimal amount, String currency, String status,
                                   PaymentProvider provider, String orderId, String approveUrl,
                                   OffsetDateTime createdAt, UUID travelId, UUID subscriptionRef) {
        super(id, userId, amount, currency, status, provider, createdAt, travelId, subscriptionRef);
        this.orderId = orderId;
        this.approveUrl = approveUrl;
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
                payment.getCreatedAt(),
                payment.getTravelId(),
                payment.getSubscriptionRef());
    }

    public String getOrderId() {
        return orderId;
    }

    public String getApproveUrl() {
        return approveUrl;
    }
}
