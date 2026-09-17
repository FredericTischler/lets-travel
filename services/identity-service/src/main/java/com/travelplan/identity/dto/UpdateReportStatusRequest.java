package com.travelplan.identity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code PATCH /reports/{id}/status}.
 *
 * Bean Validation here only guarantees the field is present and non-blank.
 * The actual allowed-value check (must be REVIEWED, DISMISSED or ACTIONED,
 * never OPEN) and the terminal-state check (current status must still be
 * OPEN) are deliberately NOT annotation-based: they depend on the report's
 * current state and must produce two distinct HTTP codes (400 vs 409), which
 * only {@link com.travelplan.identity.service.ReportService} can resolve —
 * exact same split as payment-service's {@code UpdateStatusRequest}.
 */
public class UpdateReportStatusRequest {

    @NotBlank(message = "must not be blank")
    private String status;

    public UpdateReportStatusRequest() {
        // required for Jackson deserialization
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
