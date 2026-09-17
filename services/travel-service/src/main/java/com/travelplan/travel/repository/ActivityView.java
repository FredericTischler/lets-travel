package com.travelplan.travel.repository;

import java.util.UUID;

/**
 * Raw {@code Activity} node, as read directly from Neo4j by
 * {@link ActivityRepository}.
 *
 * Not an API type: {@code DestinationService} maps this to
 * {@link com.travelplan.travel.dto.ActivityResponse}.
 */
public record ActivityView(UUID id, String name) {
}
