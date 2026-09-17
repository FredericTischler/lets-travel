package com.travelplan.travel.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data access for the {@code HAS_ACTIVITY} relationship between a
 * {@code Destination} and its {@code Activity} nodes.
 *
 * Deliberately bypasses the {@code Destination} aggregate's default Spring
 * Data Neo4j load-modify-save flow, for the same reason as
 * {@link TransportRepository} (see the Javadoc on
 * {@link com.travelplan.travel.entity.Destination#getActivities()}). No
 * business logic lives here — only data access; existence/soft-delete checks
 * on the destination are the caller's ({@code DestinationService}'s)
 * responsibility.
 */
@Repository
public class ActivityRepository {

    // Whole-list replace: every existing Activity node owned by this
    // destination is detached and deleted (they have no other owner and no
    // independent identity — see Activity's Javadoc), then the new list is
    // created from scratch. Matches PUT's "full replacement" semantics and
    // is also what POST uses (delete-nothing, then create).
    private static final String DELETE_EXISTING_QUERY = """
            MATCH (d:Destination)-[:HAS_ACTIVITY]->(a:Activity)
            WHERE d.id = $destinationId
            DETACH DELETE a
            """;

    private static final String CREATE_ONE_QUERY = """
            MATCH (d:Destination) WHERE d.id = $destinationId
            CREATE (d)-[:HAS_ACTIVITY]->(:Activity {id: $id, name: $name})
            """;

    private static final String FIND_ACTIVE_QUERY = """
            MATCH (d:Destination)-[:HAS_ACTIVITY]->(a:Activity)
            WHERE d.id = $destinationId AND d.deletedAt IS NULL
            RETURN a.id AS id, a.name AS name
            """;

    private final Neo4jClient neo4jClient;

    public ActivityRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Replace the whole set of activities owned by {@code destinationId}
     * with {@code names}. Assumes the destination already exists and is
     * active (checked by the caller).
     */
    public void replaceForDestination(UUID destinationId, List<String> names) {
        neo4jClient.query(DELETE_EXISTING_QUERY)
                .bindAll(Map.of("destinationId", destinationId.toString()))
                .run();
        for (String name : names) {
            Map<String, Object> params = new HashMap<>();
            params.put("destinationId", destinationId.toString());
            params.put("id", UUID.randomUUID().toString());
            params.put("name", name);
            neo4jClient.query(CREATE_ONE_QUERY).bindAll(params).run();
        }
    }

    /**
     * List the activities owned by an active destination.
     */
    public List<ActivityView> findActiveForDestination(UUID destinationId) {
        return neo4jClient.query(FIND_ACTIVE_QUERY)
                .bindAll(Map.of("destinationId", destinationId.toString()))
                .fetchAs(ActivityView.class)
                .mappedBy((typeSystem, record) -> new ActivityView(
                        UUID.fromString(record.get("id").asString()),
                        record.get("name").asString()))
                .all()
                .stream()
                .toList();
    }
}
