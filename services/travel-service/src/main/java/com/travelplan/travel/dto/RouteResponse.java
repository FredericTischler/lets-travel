package com.travelplan.travel.dto;

import java.util.List;

/**
 * API response for {@code GET /destinations/{fromId}/routes/{toId}}: the
 * fewest-hops chain of active {@code TRANSPORT} edges from origin to target,
 * in order (each hop's {@code destinationId} is the next stop on the way —
 * the last hop's is the final target), plus the sum of every hop's
 * {@code durationMinutes}.
 *
 * "Fewest hops", not "lowest total duration": see
 * {@code TransportRepository#PATH_QUERY} for why a true weighted
 * shortest-path is out of scope (no APOC plugin in this project).
 */
public class RouteResponse {

    private final List<TransportResponse> hops;
    private final int totalDurationMinutes;

    public RouteResponse(List<TransportResponse> hops, int totalDurationMinutes) {
        this.hops = hops;
        this.totalDurationMinutes = totalDurationMinutes;
    }

    public List<TransportResponse> getHops() {
        return hops;
    }

    public int getTotalDurationMinutes() {
        return totalDurationMinutes;
    }
}
