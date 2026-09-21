package com.travelplan.identity.service;

/**
 * Resolves the client address used as the login-throttle key.
 *
 * <p>By default only the TCP peer address is trusted. Behind Traefik every
 * request comes from the proxy, so a single bucket would lock every user out
 * together: in that deployment set {@code LOGIN_THROTTLE_TRUST_FORWARDED_FOR=true}
 * and the <em>rightmost</em> {@code X-Forwarded-For} entry is used — the one the
 * trusted proxy appended itself. Entries further left are client-supplied and
 * spoofable, so they are never read. Never enable the flag when the service is
 * reachable without the proxy: anyone could then pick their own key.</p>
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String resolve(String remoteAddr, String forwardedFor, boolean trustForwardedFor) {
        if (trustForwardedFor && forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            String last = hops[hops.length - 1].trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return remoteAddr == null ? "unknown" : remoteAddr;
    }
}
