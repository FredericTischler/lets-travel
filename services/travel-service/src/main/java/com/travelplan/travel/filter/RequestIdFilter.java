package com.travelplan.travel.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Request correlation filter: reads/generates {@code X-Request-Id}, exposes
 * it in every log line emitted while this request is being processed, and
 * echoes it back on the response.
 *
 * <p>Registered the same way as any other {@link OncePerRequestFilter} bean
 * would be in this codebase: a plain {@code @Component} picked up by Spring
 * Boot's servlet filter auto-registration — no {@code SecurityFilterChain}
 * needed (this service has none; authorization is enforced manually via
 * {@code TokenValidationService} in controllers).</p>
 *
 * <ul>
 *   <li>If the incoming request carries an {@code X-Request-Id} header, it is
 *       reused as-is (propagated from an upstream caller, e.g. Traefik).</li>
 *   <li>Otherwise a new random UUID is generated.</li>
 *   <li>The value is placed in the SLF4J {@link MDC} under {@link #MDC_KEY},
 *       which {@code logback-spring.xml}'s JSON encoder includes in every log
 *       line automatically.</li>
 *   <li>The value is set on the response under {@link #REQUEST_ID_HEADER} so
 *       the caller can see it too.</li>
 *   <li>Every request produces exactly one INFO access-log line (method,
 *       path, status, duration) carrying the requestId — without it, a
 *       normal request that hits no error path never produces any log at
 *       all, and the correlation mechanism has nothing to correlate.</li>
 *   <li>The MDC entry is always removed in a {@code finally} block, so it
 *       never leaks into a later request reusing the same worker thread.</li>
 * </ul>
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long startMillis = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Logged BEFORE MDC.remove: the JSON encoder only includes MDC
            // entries present at the moment this call is made.
            log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), System.currentTimeMillis() - startMillis);
            MDC.remove(MDC_KEY);
        }
    }
}
