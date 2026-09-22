package com.travelplan.travel.repository;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * here — only data access. Existence/soft-delete/ownership checks on the
 * origin (and, for {@code create}, the target) are the caller's
 * ({@code TransportService}'s) responsibility; this class only
 * creates/reads/updates/deletes the relationship itself.
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
                id: $id, mode: $mode, durationMinutes: $durationMinutes,
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
            RETURN t.id AS id, t.mode AS mode, t.durationMinutes AS durationMinutes,
                   t.departureTime AS departureTime, t.arrivalTime AS arrivalTime,
                   target.id AS targetId, target.name AS targetName, target.country AS targetCountry
            """;

    // PATCH semantics: only the relationship's own attributes are ever SET —
    // never the origin/target of the pattern, which would move the link
    // rather than correct it (docs/lets-travel-architecture-decisions.md §11
    // addendum). Matching on both $fromId and the relationship's own $id
    // means a caller cannot update a real transportId via the wrong fromId:
    // zero rows come back, mapped to TransportNotFoundException by the
    // service.
    private static final String UPDATE_QUERY = """
            MATCH (origin:Destination)-[t:TRANSPORT {id: $id}]->(target:Destination)
            WHERE origin.id = $fromId
            SET t.mode = $mode, t.durationMinutes = $durationMinutes,
                t.departureTime = $departureTime, t.arrivalTime = $arrivalTime
            RETURN t.id AS id, t.mode AS mode, t.durationMinutes AS durationMinutes,
                   t.departureTime AS departureTime, t.arrivalTime AS arrivalTime,
                   target.id AS targetId, target.name AS targetName, target.country AS targetCountry
            """;

    // Physical deletion of the relationship only (never DETACH DELETE, never
    // touches either node) — a TRANSPORT relationship has no lifecycle of its
    // own independent of its two nodes and nothing references it afterwards,
    // unlike a payment or a review (ADR §11 addendum "compléter le CRUD").
    private static final String DELETE_QUERY = """
            MATCH (origin:Destination)-[t:TRANSPORT {id: $id}]->(:Destination)
            WHERE origin.id = $fromId
            DELETE t
            RETURN count(t) AS deletedCount
            """;

    // Bonus pathfinding (docs/lets-travel-architecture-decisions.md §11):
    // shortest path in NUMBER OF HOPS (unweighted BFS via shortestPath()),
    // bounded by maxHops. deletedAt IS NULL is checked on EVERY node of the
    // path (origin, target, and any intermediate hop), not just the two
    // endpoints, so a soft-deleted destination in the middle of the path
    // makes it untraversable even though its own row is never returned to a
    // direct lookup. maxHops is inlined as a literal via %d (not bound as a
    // Cypher parameter): Neo4j does not accept a parameter for a variable-length
    // relationship pattern's upper bound. Safe here because the caller
    // (TransportService) validates it is a plain int in [1, 6] before this
    // method is ever called — never raw user text.
    private static final String SHORTEST_PATH_QUERY_TEMPLATE = """
            MATCH p = shortestPath((origin:Destination)-[:TRANSPORT*1..%d]->(target:Destination))
            WHERE origin.id = $fromId AND target.id = $toId
              AND ALL(n IN nodes(p) WHERE n.deletedAt IS NULL)
            WITH relationships(p) AS rels, nodes(p) AS nds
            RETURN [i IN range(0, size(rels) - 1) | {
                id: rels[i].id, mode: rels[i].mode, durationMinutes: rels[i].durationMinutes,
                departureTime: rels[i].departureTime, arrivalTime: rels[i].arrivalTime,
                targetId: nds[i + 1].id, targetName: nds[i + 1].name, targetCountry: nds[i + 1].country
            }] AS hops
            """;

    private final Neo4jClient neo4jClient;

    public TransportRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Create a directed {@code TRANSPORT} relationship from the destination
     * identified by {@code fromId} to the one identified by {@code toId}.
     * Assumes both already exist and are active (checked by the caller).
     * {@code id} is application-generated (see {@link TransportEdge}), never
     * Neo4j's internal element id.
     */
    public void create(UUID id, UUID fromId, UUID toId, String mode, int durationMinutes,
                        OffsetDateTime departureTime, OffsetDateTime arrivalTime) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id.toString());
        params.put("fromId", fromId.toString());
        params.put("toId", toId.toString());
        params.put("mode", mode);
        params.put("durationMinutes", durationMinutes);
        params.put("departureTime", departureTime);
        params.put("arrivalTime", arrivalTime);
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
                .mappedBy((typeSystem, record) -> toTransportEdge(record))
                .all()
                .stream()
                .toList();
    }

    /**
     * Update the mutable attributes of the {@code TRANSPORT} relationship
     * identified by {@code transportId}, provided it starts from
     * {@code fromId} — a mismatched {@code fromId} never matches, even if
     * {@code transportId} exists elsewhere in the graph.
     *
     * @return the updated relationship, or empty if no relationship with
     *         that id starts from {@code fromId}
     */
    public Optional<TransportEdge> update(UUID fromId, UUID transportId, String mode, int durationMinutes,
                                           OffsetDateTime departureTime, OffsetDateTime arrivalTime) {
        Map<String, Object> params = new HashMap<>();
        params.put("fromId", fromId.toString());
        params.put("id", transportId.toString());
        params.put("mode", mode);
        params.put("durationMinutes", durationMinutes);
        params.put("departureTime", departureTime);
        params.put("arrivalTime", arrivalTime);
        return neo4jClient.query(UPDATE_QUERY)
                .bindAll(params)
                .fetchAs(TransportEdge.class)
                .mappedBy((typeSystem, record) -> toTransportEdge(record))
                .first();
    }

    /**
     * Physically delete the {@code TRANSPORT} relationship identified by
     * {@code transportId}, provided it starts from {@code fromId} — same
     * mismatched-{@code fromId} guard as {@link #update}. Never touches
     * either node.
     *
     * @return {@code true} if a relationship was deleted, {@code false} if
     *         none matched
     */
    public boolean delete(UUID fromId, UUID transportId) {
        Map<String, Object> params = new HashMap<>();
        params.put("fromId", fromId.toString());
        params.put("id", transportId.toString());
        return neo4jClient.query(DELETE_QUERY)
                .bindAll(params)
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("deletedCount").asLong())
                .one()
                .orElse(0L) > 0;
    }

    /**
     * Shortest path (in number of hops) from {@code fromId} to {@code toId}
     * via outgoing {@code TRANSPORT} relationships, at most {@code maxHops}
     * long, with every node on the path required to be active
     * (non soft-deleted) — see {@link #SHORTEST_PATH_QUERY_TEMPLATE}.
     * Assumes both endpoints already verified active by the caller;
     * {@code maxHops} assumed already validated in [1, 6].
     *
     * @return the ordered list of hops (each carrying the relationship
     *         attributes used to reach it and the destination it lands on),
     *         or empty if no such path exists
     */
    public Optional<List<TransportEdge>> findShortestPath(UUID fromId, UUID toId, int maxHops) {
        String query = SHORTEST_PATH_QUERY_TEMPLATE.formatted(maxHops);
        Map<String, Object> params = new HashMap<>();
        params.put("fromId", fromId.toString());
        params.put("toId", toId.toString());
        return neo4jClient.query(query)
                .bindAll(params)
                .fetchAs(Record.class)
                .mappedBy((typeSystem, record) -> record)
                .first()
                .map(this::toHopList);
    }

    private static TransportEdge toTransportEdge(Record record) {
        return new TransportEdge(
                UUID.fromString(record.get("id").asString()),
                record.get("mode").asString(),
                record.get("durationMinutes").asInt(),
                record.get("departureTime").isNull() ? null : record.get("departureTime").asOffsetDateTime(),
                record.get("arrivalTime").isNull() ? null : record.get("arrivalTime").asOffsetDateTime(),
                UUID.fromString(record.get("targetId").asString()),
                record.get("targetName").asString(),
                record.get("targetCountry").asString());
    }

    private List<TransportEdge> toHopList(Record record) {
        List<TransportEdge> hops = new ArrayList<>();
        for (Value hop : record.get("hops").values()) {
            hops.add(new TransportEdge(
                    UUID.fromString(hop.get("id").asString()),
                    hop.get("mode").asString(),
                    hop.get("durationMinutes").asInt(),
                    hop.get("departureTime").isNull() ? null : hop.get("departureTime").asOffsetDateTime(),
                    hop.get("arrivalTime").isNull() ? null : hop.get("arrivalTime").asOffsetDateTime(),
                    UUID.fromString(hop.get("targetId").asString()),
                    hop.get("targetName").asString(),
                    hop.get("targetCountry").asString()));
        }
        return hops;
    }
}
