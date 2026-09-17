package com.travelplan.travel.entity;

import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

import java.time.OffsetDateTime;

/**
 * Neo4j relationship properties mapped to the {@code TRANSPORT} relationship
 * type, declared as an outgoing relationship on {@link Destination}.
 *
 * Directed, non-symmetric: {@code A-[TRANSPORT]->B} does not imply
 * {@code B-[TRANSPORT]->A}. If both directions exist, they are two distinct
 * relationships.
 *
 * {@code mode} is a plain String constrained to a fixed set of values
 * (TRAIN, PLANE, BUS, CAR, BOAT), enforced by
 * {@code TransportService} — same "no extensible enum, just an
 * allowed-values check in the service" approach as {@code Payment.status} in
 * payment-service.
 *
 * {@code id} is the Neo4j-internal relationship element id — never
 * application-assigned (no {@code @GeneratedValue}: unlike {@link
 * Destination#getId()}, a relationship's technical id is provided by Neo4j
 * itself at creation time) and never exposed over the API.
 *
 * {@code departureTime}/{@code arrivalTime} are optional schedule details
 * (both nullable): a transport link created without them still carries
 * {@code mode} and {@code durationMinutes}, matching the pre-existing
 * contract; callers that have the actual schedule can now record it.
 *
 * Declarative mapping only for increment 2 — see the Javadoc on
 * {@link Destination#getTransports()} for why reads/writes bypass the
 * standard Spring Data Neo4j aggregate save/load flow.
 */
@RelationshipProperties
public class Transport {

    @RelationshipId
    private Long id;

    private String mode;

    private int durationMinutes;

    private OffsetDateTime departureTime;

    private OffsetDateTime arrivalTime;

    @TargetNode
    private Destination destination;

    protected Transport() {
        // required by Spring Data Neo4j
    }

    public Transport(Destination destination, String mode, int durationMinutes,
                      OffsetDateTime departureTime, OffsetDateTime arrivalTime) {
        this.destination = destination;
        this.mode = mode;
        this.durationMinutes = durationMinutes;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
    }

    public Long getId() {
        return id;
    }

    public String getMode() {
        return mode;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public OffsetDateTime getDepartureTime() {
        return departureTime;
    }

    public OffsetDateTime getArrivalTime() {
        return arrivalTime;
    }

    public Destination getDestination() {
        return destination;
    }
}