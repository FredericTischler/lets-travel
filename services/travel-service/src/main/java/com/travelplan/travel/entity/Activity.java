package com.travelplan.travel.entity;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.util.UUID;

/**
 * Neo4j node mapped to the {@code Activity} label.
 *
 * Owned exclusively by a single {@link Destination} through the outgoing
 * {@code HAS_ACTIVITY} relationship: an {@code Activity} node has no
 * independent lifecycle or identity outside of the destination it belongs
 * to. {@code id} is application-assigned (a plain UUID), same convention as
 * {@link Destination#getId()}; uniqueness is enforced by a Cypher constraint
 * created at startup, see
 * {@link com.travelplan.travel.config.Neo4jSchemaInitializer}.
 *
 * No {@code deletedAt}: activities are never soft-deleted on their own. A
 * destination update replaces its whole activity list (see
 * {@link com.travelplan.travel.repository.ActivityRepository#replaceForDestination}),
 * and a destination soft-delete leaves existing {@code Activity} nodes and
 * their relationship physically in the graph — same "no DETACH DELETE"
 * philosophy already applied to {@code TRANSPORT} — but unreachable, since
 * the only read path is through an active destination.
 */
@Node("Activity")
public class Activity {

    @Id
    private UUID id;

    private String name;

    protected Activity() {
        // required by Spring Data Neo4j
    }

    public Activity(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
