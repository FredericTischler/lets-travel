package com.travelplan.travel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /destinations}.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.travel.exception.GlobalExceptionHandler}
 * and returned as HTTP 400. {@code activities}/{@code accommodations} are
 * optional: omitted or {@code null} is treated as an empty list. The
 * {@code endDate} >= {@code startDate} and per-accommodation
 * {@code checkOut} >= {@code checkIn} rules are business rules, not
 * annotations — checked by
 * {@link com.travelplan.travel.service.DestinationService}.
 *
 * <p>{@code managerId} follows the exact same trust-boundary pattern as
 * {@code CreateManualPaymentRequest.userId} in payment-service: it is
 * client-supplied, not derived silently from the token, and the controller
 * checks it against the caller via {@code TokenValidationService.requireOwnerOrAdmin}
 * before the request reaches the service — a {@code TRAVEL_MANAGER} may only
 * create a travel with their own id, an {@code ADMIN} may set any id (see
 * docs/lets-travel-architecture-decisions.md §2). {@code price}/{@code capacity}
 * are required so this phase leaves a clean seam for the subscription feature
 * of a later phase.</p>
 */
public class CreateDestinationRequest {

    @NotBlank(message = "must not be blank")
    private String name;

    @NotBlank(message = "must not be blank")
    private String country;

    @NotNull(message = "must not be null")
    private LocalDate startDate;

    @NotNull(message = "must not be null")
    private LocalDate endDate;

    @NotNull(message = "must not be null")
    private UUID managerId;

    @NotNull(message = "must not be null")
    @PositiveOrZero(message = "must not be negative")
    private BigDecimal price;

    @NotNull(message = "must not be null")
    @Positive(message = "must be at least 1")
    private Integer capacity;

    private List<String> activities = new ArrayList<>();

    @Valid
    private List<AccommodationRequest> accommodations = new ArrayList<>();

    public CreateDestinationRequest() {
        // required for Jackson deserialization
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

    public UUID getManagerId() {
        return managerId;
    }

    public void setManagerId(UUID managerId) {
        this.managerId = managerId;
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

    public List<String> getActivities() {
        return activities;
    }

    public void setActivities(List<String> activities) {
        this.activities = activities == null ? new ArrayList<>() : activities;
    }

    public List<AccommodationRequest> getAccommodations() {
        return accommodations;
    }

    public void setAccommodations(List<AccommodationRequest> accommodations) {
        this.accommodations = accommodations == null ? new ArrayList<>() : accommodations;
    }
}
