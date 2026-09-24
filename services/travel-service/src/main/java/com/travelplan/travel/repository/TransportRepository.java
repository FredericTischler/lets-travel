package com.travelplan.travel.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * responsibility; this class only creates/reads/mutates the relationship
 * itself.
 *
 * Each relationship carries its own app-assigned {@code id} (a UUID, same
 * convention as {@code Destination.id} — never the Neo4j-internal relationship
 * id, which SDN would expose via {@code @RelationshipId} but which this class
 * deliberately does not rely on) and its own {@code deletedAt}: update/delete
 * address one specific transport by that id, and delete never issues a
 * DETACH DELETE (same soft-delete discipline as every other aggregate).
 */
@Repository
public class TransportRepository {

    private static final int MAX_ROUTE_HOPS = 5;

    // Plain CREATE — no MERGE, no prior-existence check for an identical
    // trip. Creating the same A->B transport twice is an accepted, documented
    // gap for increment 2 (see task rationale), not an oversight. Both
    // endpoint ids are assumed already verified active by the caller.
    private static final String CREATE_QUERY = """
            MATCH (origin:Destination), (target:Destination)
            WHERE origin.id = $fromId AND target.id = $toId
            CREATE (origin)-[:TRANSPORT {
                id: $transportId, mode: $mode, durationMinutes: $durationMinutes,
                departureTime: $departureTime, arrivalTime: $arrivalTime
            }]->(target)
            """;

    // First real traversal query of the project (Phase 0 justification test).
    // deletedAt IS NULL is filtered at ALL THREE hops — origin, the
    // relationship itself, and target — so a soft-deleted target OR a
    // soft-deleted transport disappears from the result even though the
    // relationship row still physically exists (soft-delete never issues a
    // DETACH DELETE).
    private static final String OUTGOING_ACTIVE_QUERY = """
            MATCH (origin:Destination)-[t:TRANSPORT]->(target:Destination)
            WHERE origin.id = $id AND origin.deletedAt IS NULL
              AND t.deletedAt IS NULL AND target.deletedAt IS NULL
            RETURN t.id AS transportId, t.mode AS mode, t.durationMinutes AS durationMinutes,
                   t.departureTime AS departureTime, t.arrivalTime AS arrivalTime,
                   target.id AS targetId, target.name AS targetName, target.country AS targetCountry
            """;

    // target.deletedAt IS NULL matters here even though the service never
    // re-validates the target on update (only origin ownership + the
    // transport's own id): without it, a target soft-deleted after this
    // transport was created would still have its id/name/country returned in
    // the response of a successful PUT, even though the same destination is
    // correctly hidden from GET .../transports (OUTGOING_ACTIVE_QUERY above).
    private static final String UPDATE_QUERY = """
            MATCH (origin:Destination)-[t:TRANSPORT]->(target:Destination)
            WHERE origin.id = $fromId AND t.id = $transportId
              AND t.deletedAt IS NULL AND target.deletedAt IS NULL
            SET t.mode = $mode, t.durationMinutes = $durationMinutes,
                t.departureTime = $departureTime, t.arrivalTime = $arrivalTime
            RETURN t.id AS transportId, t.mode AS mode, t.durationMinutes AS durationMinutes,
                   t.departureTime AS departureTime, t.arrivalTime AS arrivalTime,
                   target.id AS targetId, target.name AS targetName, target.country AS targetCountry
            """;

    // Soft-delete: marks the relationship deletedAt, never DETACH DELETE —
    // same discipline as Destination's own delete. Idempotent: deleting an
    // already soft-deleted (or non-existent) transport matches zero rows,
    // which the caller (TransportService) turns into a 404, same as
    // Destination's delete-twice behaviour. The target is intentionally NOT
    // filtered by deletedAt here (unlike UPDATE_QUERY): no target property is
    // ever read or returned by a delete, so a soft-deleted target can't leak
    // through it — deleting a transport whose target already vanished is a
    // harmless no-op-adjacent outcome, not a gap.
    private static final String SOFT_DELETE_QUERY = """
            MATCH (origin:Destination)-[t:TRANSPORT]->(:Destination)
            WHERE origin.id = $fromId AND t.id = $transportId AND t.deletedAt IS NULL
            SET t.deletedAt = $now
            RETURN t.id AS transportId
            """;

    // Multi-hop pathfinding: the shortest (fewest-hops) chain of active
    // TRANSPORT edges from origin to target, bounded to MAX_ROUTE_HOPS.
    //
    // Deliberately NOT Cypher's built-in shortestPath(): that function picks
    // ONE candidate path during graph traversal and only THEN applies the
    // WHERE clause — if that candidate happens to cross a soft-deleted node
    // or relationship, the whole query returns nothing even when a slightly
    // longer, fully active path exists. Filtering nodes/relationships with
    // ALL(...) predicates BEFORE ranking by length(path) avoids that pitfall,
    // at the cost of enumerating more candidate paths — an acceptable
    // trade-off at this project's graph size, bounded by MAX_ROUTE_HOPS.
    //
    // This ranks by fewest hops, not by lowest total duration (a true
    // weighted-shortest-path/Dijkstra would need the APOC plugin, not
    // installed in this project — see docs/lets-travel-architecture-decisions.md).
    private static final String PATH_QUERY = """
            MATCH path = (origin:Destination)-[:TRANSPORT*1..%d]->(target:Destination)
            WHERE origin.id = $fromId AND target.id = $toId
              AND ALL(n IN nodes(path) WHERE n.deletedAt IS NULL)
              AND ALL(r IN relationships(path) WHERE r.deletedAt IS NULL)
            WITH path, relationships(path) AS rels, nodes(path) AS pathNodes
            ORDER BY length(path) ASC
            LIMIT 1
            UNWIND range(0, size(rels) - 1) AS i
            RETURN rels[i].id AS transportId, rels[i].mode AS mode, rels[i].durationMinutes AS durationMinutes,
                   rels[i].departureTime AS departureTime, rels[i].arrivalTime AS arrivalTime,
                   pathNodes[i + 1].id AS targetId, pathNodes[i + 1].name AS targetName,
                   pathNodes[i + 1].country AS targetCountry
            """.formatted(MAX_ROUTE_HOPS);

    private static final String DEPARTURE_TIME = "departureTime";
    private static final String ARRIVAL_TIME = "arrivalTime";
    private static final String FROM_ID = "fromId";
    private static final String TRANSPORT_ID = "transportId";
    private static final String DURATION_MINUTES = "durationMinutes";

    private final Neo4jClient neo4jClient;

    public TransportRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Create a directed {@code TRANSPORT} relationship from the destination
     * identified by {@code fromId} to the one identified by {@code toId}.
     * Assumes both already exist and are active (checked by the caller).
     *
     * @return the new transport's app-assigned id
     */
    public UUID create(UUID fromId, UUID toId, String mode, int durationMinutes,
                        OffsetDateTime departureTime, OffsetDateTime arrivalTime) {
        UUID transportId = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        params.put(FROM_ID, fromId.toString());
        params.put("toId", toId.toString());
        params.put(TRANSPORT_ID, transportId.toString());
        params.put("mode", mode);
        params.put(DURATION_MINUTES, durationMinutes);
        params.put(DEPARTURE_TIME, departureTime);
        params.put(ARRIVAL_TIME, arrivalTime);
        neo4jClient.query(CREATE_QUERY).bindAll(params).run();
        return transportId;
    }

    /**
     * One-hop traversal: destinations reachable from {@code id} via an
     * outgoing {@code TRANSPORT} relationship, filtered to active (non
     * soft-deleted) origin, transport and target.
     */
    public List<TransportEdge> findActiveOutgoing(UUID id) {
        return runEdgeQuery(OUTGOING_ACTIVE_QUERY, Map.of("id", id.toString()));
    }

    /**
     * Update the mutable fields (mode, durationMinutes, departure/arrival
     * time) of one active transport, addressed by its own id — never its
     * endpoints, which stay fixed for the lifetime of the relationship (a
     * different route is a different transport, not an edit of this one).
     *
     * @return the updated edge, or empty if no active transport with that id
     *         hangs off {@code fromId} (absent, already soft-deleted, or
     *         belongs to a different origin)
     */
    public Optional<TransportEdge> update(UUID fromId, UUID transportId, String mode, int durationMinutes,
                                           OffsetDateTime departureTime, OffsetDateTime arrivalTime) {
        Map<String, Object> params = new HashMap<>();
        params.put(FROM_ID, fromId.toString());
        params.put(TRANSPORT_ID, transportId.toString());
        params.put("mode", mode);
        params.put(DURATION_MINUTES, durationMinutes);
        params.put(DEPARTURE_TIME, departureTime);
        params.put(ARRIVAL_TIME, arrivalTime);
        return runEdgeQuery(UPDATE_QUERY, params).stream().findFirst();
    }

    /**
     * Soft-delete one active transport, addressed by its own id.
     *
     * @return {@code true} if an active transport matched (and was marked
     *         deleted), {@code false} if none did (absent, already deleted,
     *         or belongs to a different origin)
     */
    public boolean softDelete(UUID fromId, UUID transportId, OffsetDateTime now) {
        Map<String, Object> params = new HashMap<>();
        params.put(FROM_ID, fromId.toString());
        params.put(TRANSPORT_ID, transportId.toString());
        params.put("now", now);
        return neo4jClient.query(SOFT_DELETE_QUERY)
                .bindAll(params)
                .fetchAs(String.class)
                .one()
                .isPresent();
    }

    /**
     * The fewest-hops chain of active {@code TRANSPORT} edges from
     * {@code fromId} to {@code toId} (at most {@link #MAX_ROUTE_HOPS} hops),
     * in order, or an empty list if no such chain exists. See
     * {@link #PATH_QUERY} for why this is not built on {@code shortestPath()}.
     */
    public List<TransportEdge> findPath(UUID fromId, UUID toId) {
        return runEdgeQuery(PATH_QUERY, Map.of(FROM_ID, fromId.toString(), "toId", toId.toString()));
    }

    private List<TransportEdge> runEdgeQuery(String query, Map<String, Object> params) {
        return neo4jClient.query(query)
                .bindAll(params)
                .fetchAs(TransportEdge.class)
                .mappedBy((typeSystem, row) -> new TransportEdge(
                        UUID.fromString(row.get(TRANSPORT_ID).asString()),
                        row.get("mode").asString(),
                        row.get(DURATION_MINUTES).asInt(),
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
