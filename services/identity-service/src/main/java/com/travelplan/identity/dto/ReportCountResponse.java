package com.travelplan.identity.dto;

/**
 * API response for {@code GET /reports/count/{userId}}.
 *
 * Deliberately carries only a count, never the underlying report contents
 * (reason/status/reporter identity) — see
 * {@link com.travelplan.identity.service.ReportService#countByReportedUserId}
 * javadoc for why this endpoint is open to any authenticated role.
 */
public class ReportCountResponse {

    private final long count;

    public ReportCountResponse(long count) {
        this.count = count;
    }

    public long getCount() {
        return count;
    }
}
