package com.travelplan.travel.repository;

import com.travelplan.travel.entity.Destination;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Destination}.
 *
 * All query methods filter on {@code deletedAt IS NULL} to honour the
 * soft-delete contract. No business logic lives here — only data access.
 */
public interface DestinationRepository extends Neo4jRepository<Destination, UUID> {

    /**
     * Find a non-deleted destination by id.
     */
    @Query("MATCH (d:Destination) WHERE d.id = $id AND d.deletedAt IS NULL RETURN d")
    Optional<Destination> findActiveById(@Param("id") UUID id);

    /**
     * Return all non-deleted destinations.
     */
    @Query("MATCH (d:Destination) WHERE d.deletedAt IS NULL RETURN d")
    List<Destination> findAllActive();
}