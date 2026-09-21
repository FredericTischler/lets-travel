package com.travelplan.travel.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Raw {@code GAVE_FEEDBACK} relation row, as read directly from Neo4j by
 * {@link FeedbackRepository} — one traveler's feedback on one destination,
 * plus a small summary of the destination so that every consumer (traveler
 * profile, manager quality control, admin history) can display a row without
 * a second round trip.
 *
 * Not an API type: {@code FeedbackService} maps this to
 * {@link com.travelplan.travel.dto.FeedbackResponse}.
 */
public record FeedbackView(UUID id, UUID travelerId, UUID destinationId, String destinationName,
                            String destinationCountry, LocalDate destinationEndDate,
                            int rating, String comment, OffsetDateTime createdAt) {
}
