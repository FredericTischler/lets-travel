package com.travelplan.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for {@code POST /reports}.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.identity.exception.GlobalExceptionHandler}
 * and returned as HTTP 400.
 *
 * Intentionally has NO {@code reporterId} field: the reporter is always the
 * caller identified by their own Bearer token
 * ({@link com.travelplan.identity.service.ReportService#create}), never a
 * client-supplied value — same "server sets it from the token, not the body"
 * principle payment-service's {@code TokenValidationService#requireOwnerOrAdmin}
 * established for ownership. Also has NO {@code status}: a new report always
 * starts {@code OPEN} (see {@link com.travelplan.identity.entity.Report}).
 */
public class CreateReportRequest {

    @NotNull(message = "must not be null")
    private UUID reportedUserId;

    @NotBlank(message = "must not be blank")
    @Size(max = 2000, message = "must be at most 2000 characters long")
    private String reason;

    public CreateReportRequest() {
        // required for Jackson deserialization
    }

    public UUID getReportedUserId() {
        return reportedUserId;
    }

    public void setReportedUserId(UUID reportedUserId) {
        this.reportedUserId = reportedUserId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
