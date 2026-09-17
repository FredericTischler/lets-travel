package com.travelplan.travel.entity;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Neo4j node mapped to the {@code Accommodation} label.
 *
 * Owned exclusively by a single {@link Destination} through the outgoing
 * {@code HAS_ACCOMMODATION} relationship — same ownership model as
 * {@link Activity}, see its Javadoc for the rationale (no independent
 * lifecycle, no {@code deletedAt}, whole-list replace on update, orphaned
 * but unreachable after a destination soft-delete).
 *
 * {@code checkIn}/{@code checkOut} are optional: a stay is assumed to span
 * the whole destination visit unless these are explicitly given to represent
 * a distinct sub-period.
 */
@Node("Accommodation")
public class Accommodation {

    @Id
    private UUID id;

    private String name;

    private String type;

    private LocalDate checkIn;

    private LocalDate checkOut;

    protected Accommodation() {
        // required by Spring Data Neo4j
    }

    public Accommodation(String name, String type, LocalDate checkIn, LocalDate checkOut) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }
}
