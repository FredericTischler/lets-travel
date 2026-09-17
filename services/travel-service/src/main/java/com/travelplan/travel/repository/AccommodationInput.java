package com.travelplan.travel.repository;

import java.time.LocalDate;

/**
 * Data needed to create one {@code Accommodation} node, as passed from
 * {@code DestinationService} to {@link AccommodationRepository}. Kept
 * separate from the {@code dto} package so the repository layer never
 * depends on API-facing types.
 */
public record AccommodationInput(String name, String type, LocalDate checkIn, LocalDate checkOut) {
}
