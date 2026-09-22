package com.travelplan.travel.dto;

import java.util.List;

/**
 * Response for {@code GET /destinations/{fromId}/routes/{toId}}
 * (docs/lets-travel-architecture-decisions.md §11): the shortest path, in
 * number of hops (not weighted by duration — see the ADR's "Ce que je
 * sacrifie"), between two active destinations via outgoing {@code TRANSPORT}
 * relationships, or {@code reachable: false} if none exists within
 * {@code maxHops}.
 *
 * Always a 200: by the time this is built, both {@code fromId} and
 * {@code toId} were already confirmed to exist and be active — the absence
 * of a path between two real destinations is not the absence of a resource.
 */
public class RouteResponse {

    private final boolean reachable;
    private final List<RouteHop> hops;
    private final Integer totalDurationMinutes;

    private RouteResponse(boolean reachable, List<RouteHop> hops, Integer totalDurationMinutes) {
        this.reachable = reachable;
        this.hops = hops;
        this.totalDurationMinutes = totalDurationMinutes;
    }

    public static RouteResponse unreachable() {
        return new RouteResponse(false, List.of(), null);
    }

    public static RouteResponse of(List<RouteHop> hops, int totalDurationMinutes) {
        return new RouteResponse(true, hops, totalDurationMinutes);
    }

    public boolean isReachable() {
        return reachable;
    }

    public List<RouteHop> getHops() {
        return hops;
    }

    public Integer getTotalDurationMinutes() {
        return totalDurationMinutes;
    }
}
