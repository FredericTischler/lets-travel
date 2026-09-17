package com.travelplan.payment.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code PATCH /payments/{id}/status}.
 *
 * Bean Validation here only guarantees the field is present and non-blank.
 * The actual allowed-value check (must be COMPLETED or FAILED, never PENDING)
 * and the terminal-state check (current status must still be PENDING) are
 * deliberately NOT annotation-based: they depend on the payment's current
 * state and must produce two distinct HTTP codes (400 vs 409), which only
 * {@link com.travelplan.payment.service.PaymentService} can resolve.
 */
public class UpdateStatusRequest {

    @NotBlank(message = "must not be blank")
    private String status;

    public UpdateStatusRequest() {
        // required for Jackson deserialization
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}