package com.travelplan.travel.exception;

import java.time.LocalDate;

/**
 * Thrown when a {@code POST /destinations} or {@code PUT /destinations/{id}}
 * request violates a destination-level business rule that Bean Validation
 * alone cannot express: {@code endDate} before {@code startDate}, or an
 * accommodation's {@code checkOut} before its {@code checkIn}.
 *
 * Mapped to HTTP 400 via {@link GlobalExceptionHandler} — same "business
 * rule checked in the service, not via annotations" approach as
 * {@link InvalidTransportRequestException}.
 */
public class InvalidDestinationRequestException extends RuntimeException {

    private InvalidDestinationRequestException(String message) {
        super(message);
    }

    public static InvalidDestinationRequestException endDateBeforeStartDate(LocalDate startDate, LocalDate endDate) {
        return new InvalidDestinationRequestException(
                "endDate must not be before startDate: startDate=" + startDate + ", endDate=" + endDate);
    }

    public static InvalidDestinationRequestException accommodationCheckOutBeforeCheckIn(
            LocalDate checkIn, LocalDate checkOut) {
        return new InvalidDestinationRequestException(
                "Accommodation checkOut must not be before checkIn: checkIn=" + checkIn + ", checkOut=" + checkOut);
    }
}
