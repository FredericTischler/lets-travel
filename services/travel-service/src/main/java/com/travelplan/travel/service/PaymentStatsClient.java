package com.travelplan.travel.service;

import com.travelplan.travel.dto.PaymentIncomeReport;
import com.travelplan.travel.dto.PaymentSummaryReport;
import com.travelplan.travel.filter.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only calls to payment-service for the dashboards and statistics
 * (docs/lets-travel-architecture-decisions.md, "Dashboards" addendum) — the
 * money half that travel-service does not own.
 *
 * <p>Two calls, two credentials, both scoped as narrowly as the existing
 * ones:</p>
 * <ul>
 *   <li>{@link #fetchIncome()} — {@code GET /payments/income} with the
 *       {@code service:travel} <b>service token</b>: the aggregate spans every
 *       traveler's payments, which no manager or traveler may read directly;
 *       travel-service filters it down to the caller's own travels.</li>
 *   <li>{@link #fetchSummary} — {@code GET /payments/summary} with the
 *       <b>caller's own bearer token</b> forwarded (same as
 *       {@link PaymentServiceClient}): payment-service itself then enforces
 *       "own summary only unless admin".</li>
 * </ul>
 *
 * <p><b>Best-effort, never throws:</b> unlike {@link PaymentServiceClient} (a
 * payment must exist for a checkout to make sense), a dashboard is still useful
 * without its money figures. Any failure (unreachable, timeout, non-2xx,
 * undecodable body) is logged and returned as an empty {@link Optional}; callers
 * degrade to {@code null} fields plus {@code partial: true}. Short timeouts
 * (3 s connect / 5 s read), no retry, {@code X-Request-Id} propagated.</p>
 */
@Component
public class PaymentStatsClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatsClient.class);

    private final RestClient restClient;
    private final JwtService jwtService;

    public PaymentStatsClient(JwtService jwtService, @Value("${payment-service.url}") String paymentServiceUrl) {
        this.jwtService = jwtService;
        this.restClient = RestClient.builder()
                .baseUrl(paymentServiceUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(Duration.ofSeconds(3))
                                .withReadTimeout(Duration.ofSeconds(5))))
                .build();
    }

    /** The platform-wide income aggregate, empty if payment-service could not provide it. */
    public Optional<PaymentIncomeReport> fetchIncome() {
        try {
            PaymentIncomeReport report = restClient.get()
                    .uri("/payments/income")
                    .headers(headers -> {
                        headers.setBearerAuth(jwtService.generateServiceToken());
                        propagateRequestId(headers);
                    })
                    .retrieve()
                    .body(PaymentIncomeReport.class);
            if (report == null || report.rows() == null) {
                log.warn("payment-service returned an empty income aggregate");
                return Optional.empty();
            }
            return Optional.of(report);
        } catch (RestClientException ex) {
            log.warn("Income figures unavailable, dashboard will be partial: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@code userId}'s completed-payment summary, empty if payment-service could
     * not provide it.
     *
     * @param authorizationHeader the caller's own {@code Authorization} header, forwarded as-is
     */
    public Optional<PaymentSummaryReport> fetchSummary(String authorizationHeader, UUID userId) {
        try {
            PaymentSummaryReport report = restClient.get()
                    .uri("/payments/summary?userId={userId}", userId)
                    .headers(headers -> {
                        headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
                        propagateRequestId(headers);
                    })
                    .retrieve()
                    .body(PaymentSummaryReport.class);
            return Optional.ofNullable(report);
        } catch (RestClientException ex) {
            log.warn("Payment summary of {} unavailable, statistics will be partial: {}", userId, ex.getMessage());
            return Optional.empty();
        }
    }

    private static void propagateRequestId(HttpHeaders headers) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            headers.add(RequestIdFilter.REQUEST_ID_HEADER, requestId);
        }
    }
}
