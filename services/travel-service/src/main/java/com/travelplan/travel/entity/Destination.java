package com.travelplan.travel.entity;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Neo4j node mapped to the {@code Destination} label.
 *
 * {@code id} is application-assigned (a plain UUID, no
 * {@code @GeneratedValue} — Neo4j's internal element id is a separate,
 * opaque implementation detail we never expose). Uniqueness of {@code id} is
 * enforced by a Cypher constraint created at startup, see
 * {@link com.travelplan.travel.config.Neo4jSchemaInitializer}.
 *
 * Soft-delete pattern: nodes are never physically removed. The service sets
 * {@code deletedAt} to mark a destination as inactive. Queries filtering
 * active destinations always include {@code WHERE d.deletedAt IS NULL} — same
 * fondation as {@code User}/{@code Payment} in identity-service/payment-service.
 *
 * Increment 2 adds the outgoing {@code TRANSPORT} relationship — see
 * {@link #getTransports()} for why it is declarative-only. {@code
 * startDate}/{@code endDate} and the outgoing {@code HAS_ACTIVITY}/{@code
 * HAS_ACCOMMODATION} relationships follow the same declarative-only
 * convention for the relationship fields — see {@link #getActivities()}.
 * Duration is deliberately not a stored field: it is always derived from
 * {@code startDate}/{@code endDate} (see {@link #getDurationDays()}) so the
 * two can never drift apart.
 *
 * <p>Per docs/lets-travel-architecture-decisions.md §2, this node is also the
 * "Travel" of the subject's Travel Manager/Traveler requirements — a
 * dedicated {@code Travel} node was considered and rejected as needless
 * duplication, since {@code Destination} already carries everything a travel
 * needs except ownership and price/capacity. {@code managerId} is an
 * applicative reference to a {@code TRAVEL_MANAGER} {@code User} id in
 * identity-service (no FK, no cross-store lookup — same principle as
 * {@code Payment.userId} in payment-service) and is immutable once set: it is
 * assigned at creation and never changed by {@code PUT}. {@code price}/
 * {@code capacity} exist to leave a clean seam for the subscription feature
 * of a later phase; this phase does not implement subscriptions itself.</p>
 */
@Node("Destination")
public class Destination {

    @Id
    private UUID id;

    private String name;

    private String country;

    private LocalDate startDate;

    private LocalDate endDate;

    private UUID managerId;

    private BigDecimal price;

    private Integer capacity;

    private OffsetDateTime createdAt;

    private OffsetDateTime deletedAt;

    /**
     * Outgoing {@code TRANSPORT} relationships to other destinations.
     *
     * Declarative mapping only: it documents the domain model, but increment
     * 2 deliberately does NOT read or write this relationship through Spring
     * Data Neo4j's standard load-modify-save aggregate flow. SDN persists a
     * {@code @Relationship} collection by deleting every existing
     * relationship of that type from the node and recreating it from the
     * in-memory list on every {@code save()}. Combined with the deliberate
     * "no anti-duplicate protection" decision for TRANSPORT (see
     * {@link com.travelplan.travel.repository.TransportRepository}), any
     * write path that loads a partial/lazily-fetched aggregate and saves it
     * back would silently wipe sibling relationships it never loaded. To
     * avoid that footgun, both creation and one-hop traversal go through
     * explicit Cypher in {@code TransportRepository} instead.
     */
    @Relationship(type = "TRANSPORT", direction = Relationship.Direction.OUTGOING)
    private List<Transport> transports = new ArrayList<>();

    /**
     * Outgoing {@code HAS_ACTIVITY} relationships to this destination's
     * activities.
     *
     * Declarative mapping only, same rationale as {@link #getTransports()}:
     * {@code findActiveById}/{@code findAllActive} in
     * {@link com.travelplan.travel.repository.DestinationRepository} only
     * {@code RETURN d}, so this collection is never populated by the
     * standard SDN load path. Reads and the whole-list replace performed on
     * update both go through explicit Cypher in
     * {@link com.travelplan.travel.repository.ActivityRepository} instead —
     * this field exists purely to document the domain model.
     */
    @Relationship(type = "HAS_ACTIVITY", direction = Relationship.Direction.OUTGOING)
    private List<Activity> activities = new ArrayList<>();

    /**
     * Outgoing {@code HAS_ACCOMMODATION} relationships to this destination's
     * accommodations. Declarative-only, same rationale as
     * {@link #getActivities()}; actual access goes through
     * {@link com.travelplan.travel.repository.AccommodationRepository}.
     */
    @Relationship(type = "HAS_ACCOMMODATION", direction = Relationship.Direction.OUTGOING)
    private List<Accommodation> accommodations = new ArrayList<>();

    protected Destination() {
        // required by Spring Data Neo4j
    }

    public Destination(String name, String country, LocalDate startDate, LocalDate endDate,
                        UUID managerId, BigDecimal price, Integer capacity) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.country = country;
        this.startDate = startDate;
        this.endDate = endDate;
        this.managerId = managerId;
        this.price = price;
        this.capacity = capacity;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    /**
     * Applicative reference to the {@code TRAVEL_MANAGER} user (identity-service)
     * who owns this travel. Immutable once set — assigned at creation, never
     * changed by an update.
     */
    public UUID getManagerId() {
        return managerId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    /**
     * Trip duration in days, inclusive of both the start and end date, e.g.
     * a stay from Monday to Wednesday is 3 days. {@code null} if either date
     * is missing.
     */
    public Long getDurationDays() {
        if (startDate == null || endDate == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public List<Transport> getTransports() {
        return transports;
    }

    public List<Activity> getActivities() {
        return activities;
    }

    public List<Accommodation> getAccommodations() {
        return accommodations;
    }
}