package com.travelplan.payment.dto;

/**
 * Request body for {@code POST /payments}.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.payment.exception.GlobalExceptionHandler}
 * and returned as HTTP 400. Fields/validation live in {@link CreatePaymentRequest}.
 *
 * Intentionally has NO {@code status} field: a manually-created payment is
 * always created as PENDING — {@link com.travelplan.payment.service.PaymentService}
 * forces this value and never reads a client-supplied status at creation time.
 */
public class CreateManualPaymentRequest extends CreatePaymentRequest {

    public CreateManualPaymentRequest() {
        // required for Jackson deserialization
    }
}
