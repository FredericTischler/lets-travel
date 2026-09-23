package com.travelplan.travel.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data access for the {@code TRANSPORT} relationship.
 *
 * Deliberately bypasses the {@code Destination} aggregate's default Spring
 * Data Neo4j load-modify-save flow (see the Javadoc on
 * {@link com.travelplan.travel.entity.Destination#getTransports()}) in
 * favour of explicit Cypher via {@link Neo4jClient}. No business logic lives
 * here — only data access. Existence/soft-delete checks on the origin and
 * target destinations are the caller's ({@code TransportService}'s)
 * responsibility; this class only creates/reads the relationship itself.
 */
@Repository
public class TransportRepository {

    // Plain CREATE — no MERGE, no prior-existence check for an identical
    // trip. Creating the same A->B transport twice is an accepted, documented
    // gap for increment 2 (see task rationale), not an oversight. Both
    // endpoint ids are assumed already verified active by the caller.
    private static final String CREATE_QUERY = """
            MATCH (origin:Destination), (target:Destination)
            WHERE origin.id = $fromId AND target.id = $toId
            CREATE (origin)-[:TRANSPORT {
                mode: $mode, durationMinutes: $durationMinutes,
                departureTime: $departureTime, arrivalTime: $arrivalTime
            }]->(target)
            """;

    // First real traversal query of the project (Phase 0 justification test).
    // deletedAt IS NULL is filtered at BOTH hops — origin and target — so a
    // soft-deleted target disappears from the result even though its
    // relationship row still physically exists (soft-delete never issues a
    // DETACH DELETE).
    private static final String OUTGOING_ACTIVE_QUERY = """
            MATCH (origin:Destination)-[t:TRANSPORT]->(target:Destination)
            WHERE origin.id = $id AND origin.deletedAt IS NULL AND target.deletedAt IS NULL
            RETURN t.mode AS mode, t.durationMinutes AS durationMinutes,
                   t.departureTime AS departureTime, t.arrivalTime AS arrivalTime,
                   target.id AS targetId, target.name AS targetName, target.country AS targetCountry
            """;

    private static final String DEPARTURE_TIME = "departureTime";
    private static final String ARRIVAL_TIME = "arrivalTime";

    private final Neo4jClient neo4jClient;

    public TransportRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Create a directed {@code TRANSPORT} relationship from the destination
     * identified by {@code fromId} to the one identified by {@code toId}.
     * Assumes both already exist and are active (checked by the caller).
     */
    public void create(UUID fromId, UUID toId, String mode, int durationMinutes,
                        OffsetDateTime departureTime, OffsetDateTime arrivalTime) {
        Map<String, Object> params = new HashMap<>();
        params.put("fromId", fromId.toString());
        params.put("toId", toId.toString());
        params.put("mode", mode);
        params.put("durationMinutes", durationMinutes);
        params.put(DEPARTURE_TIME, departureTime);
        params.put(ARRIVAL_TIME, arrivalTime);
        neo4jClient.query(CREATE_QUERY).bindAll(params).run();
    }

    /**
     * One-hop traversal: destinations reachable from {@code id} via an
     * outgoing {@code TRANSPORT} relationship, filtered to active (non
     * soft-deleted) origin and target.
     */
    public List<TransportEdge> findActiveOutgoing(UUID id) {
        return neo4jClient.query(OUTGOING_ACTIVE_QUERY)
                .bindAll(Map.of("id", id.toString()))
                .fetchAs(TransportEdge.class)
                .mappedBy((typeSystem, row) -> new TransportEdge(
                        row.get("mode").asString(),
                        row.get("durationMinutes").asInt(),
                        row.get(DEPARTURE_TIME).isNull() ? null : row.get(DEPARTURE_TIME).asOffsetDateTime(),
                        row.get(ARRIVAL_TIME).isNull() ? null : row.get(ARRIVAL_TIME).asOffsetDateTime(),
                        UUID.fromString(row.get("targetId").asString()),
                        row.get("targetName").asString(),
                        row.get("targetCountry").asString()))
                .all()
                .stream()
                .toList();
    }
}