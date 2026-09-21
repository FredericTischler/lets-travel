package com.travelplan.travel.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /destinations/{id}/feedback}.
 *
 * Deliberately has no {@code travelerId}: the author is always the caller's
 * JWT subject (same trust-boundary rule as subscriptions), so a client cannot
 * write feedback in someone else's name.
 *
 * <p>Everything here is structural, so Bean Validation is enough (unlike
 * {@link CreateTransportRequest}, where business rules need specific 400
 * messages): {@code rating} is an integer 1..5 (a non-integer JSON value such
 * as {@code 4.5} is rejected by {@link StrictIntegerDeserializer} rather than
 * truncated), {@code comment} is optional but, when present, must contain at
 * least one non-whitespace character and be at most {@value #COMMENT_MAX_LENGTH}
 * characters. The comment is plain text and stored verbatim — see
 * docs/lets-travel-architecture-decisions.md §5 addendum for the XSS
 * stance.</p>
 */
public class GiveFeedbackRequest {

    public static final int COMMENT_MAX_LENGTH = 1000;

    @NotNull(message = "must not be null")
    @Min(value = 1, message = "must be between 1 and 5")
    @Max(value = 5, message = "must be between 1 and 5")
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer rating;

    // (?s) so a multi-line comment still matches; \S anywhere means "not blank".
    // A null comment is valid (comment is optional) — @Pattern/@Size ignore null.
    @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank when provided")
    @Size(max = COMMENT_MAX_LENGTH, message = "must be at most " + COMMENT_MAX_LENGTH + " characters long")
    private String comment;

    public GiveFeedbackRequest() {
        // required for Jackson deserialization
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
