package com.travelplan.travel.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data access for the {@code HAS_ACCOMMODATION} relationship between a
 * {@code Destination} and its {@code Accommodation} nodes.
 *
 * Same rationale/pattern as {@link ActivityRepository}: bypasses the
 * standard Spring Data Neo4j aggregate load-modify-save flow via explicit
 * Cypher, whole-list replace on update, no business logic here.
 */
@Repository
public class AccommodationRepository {

    private static final String DELETE_EXISTING_QUERY = """
            MATCH (d:Destination)-[:HAS_ACCOMMODATION]->(a:Accommodation)
            WHERE d.id = $destinationId
            DETACH DELETE a
            """;

    private static final String CREATE_ONE_QUERY = """
            MATCH (d:Destination) WHERE d.id = $destinationId
            CREATE (d)-[:HAS_ACCOMMODATION]->(:Accommodation {
                id: $id, name: $name, type: $type, checkIn: $checkIn, checkOut: $checkOut
            })
            """;

    private static final String FIND_ACTIVE_QUERY = """
            MATCH (d:Destination)-[:HAS_ACCOMMODATION]->(a:Accommodation)
            WHERE d.id = $destinationId AND d.deletedAt IS NULL
            RETURN a.id AS id, a.name AS name, a.type AS type, a.checkIn AS checkIn, a.checkOut AS checkOut
            """;

    private final Neo4jClient neo4jClient;

    public AccommodationRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Replace the whole set of accommodations owned by {@code destinationId}
     * with {@code accommodations}. Assumes the destination already exists
     * and is active (checked by the caller).
     */
    public void replaceForDestination(UUID destinationId, List<AccommodationInput> accommodations) {
        neo4jClient.query(DELETE_EXISTING_QUERY)
                .bindAll(Map.of("destinationId", destinationId.toString()))
                .run();
        for (AccommodationInput accommodation : accommodations) {
            Map<String, Object> params = new HashMap<>();
            params.put("destinationId", destinationId.toString());
            params.put("id", UUID.randomUUID().toString());
            params.put("name", accommodation.name());
            params.put("type", accommodation.type());
            params.put("checkIn", accommodation.checkIn());
            params.put("checkOut", accommodation.checkOut());
            neo4jClient.query(CREATE_ONE_QUERY).bindAll(params).run();
        }
    }

    /**
     * List the accommodations owned by an active destination.
     */
    public List<AccommodationView> findActiveForDestination(UUID destinationId) {
        return neo4jClient.query(FIND_ACTIVE_QUERY)
                .bindAll(Map.of("destinationId", destinationId.toString()))
                .fetchAs(AccommodationView.class)
                .mappedBy((typeSystem, record) -> new AccommodationView(
                        UUID.fromString(record.get("id").asString()),
                        record.get("name").asString(),
                        record.get("type").asString(),
                        record.get("checkIn").isNull() ? null : record.get("checkIn").asLocalDate(),
                        record.get("checkOut").isNull() ? null : record.get("checkOut").asLocalDate()))
                .all()
                .stream()
                .toList();
    }
}
