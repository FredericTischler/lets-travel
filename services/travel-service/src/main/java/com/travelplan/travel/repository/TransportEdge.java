package com.travelplan.travel.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Raw one-hop {@code TRANSPORT} traversal result, as read directly from
 * Neo4j by {@link TransportRepository}.
 *
 * Not an API type: {@code TransportService} maps this to
 * {@link com.travelplan.travel.dto.TransportResponse}.
 */
public record TransportEdge(String mode, int durationMinutes, OffsetDateTime departureTime,
                             OffsetDateTime arrivalTime, UUID targetId, String targetName,
                             String targetCountry) {
}
