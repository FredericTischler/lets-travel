package com.travelplan.travel.repository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Raw {@code Accommodation} node, as read directly from Neo4j by
 * {@link AccommodationRepository}.
 *
 * Not an API type: {@code DestinationService} maps this to
 * {@link com.travelplan.travel.dto.AccommodationResponse}.
 */
public record AccommodationView(UUID id, String name, String type, LocalDate checkIn, LocalDate checkOut) {
}
