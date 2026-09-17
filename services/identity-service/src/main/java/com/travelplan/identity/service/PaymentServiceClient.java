package com.travelplan.identity.service;

import com.travelplan.identity.filter.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.UUID;

/**
 * Cascades a user soft-delete to payment-service by calling
 * {@code DELETE /payments/by-user/{userId}}.
 *
 * <p><b>Reconciliation debt — assumed, not a bug:</b> no retry, no queue, no
 * distributed transaction. This is called AFTER {@code UserService.delete()}
 * has already committed (the user is soft-deleted either way). If this call
 * fails (payment-service down, network error, timeout, non-2xx response),
 * the failure is logged and swallowed: the user stays deleted and their
 * payments are orphaned until a manual/future reconciliation pass. See
 * {@link com.travelplan.identity.controller.UserController#delete}.</p>
 *
 * <p><b>Auth:</b> authenticates with a short-lived service-to-service JWT
 * (subject {@code service:identity}, see {@link JwtService#generateServiceToken()})
 * that payment-service accepts ONLY on this one endpoint.</p>
 */
@Component
public class PaymentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceClient.class);

    private final RestClient restClient;
    private final JwtService jwtService;

    public PaymentServiceClient(JwtService jwtService,
            @Value("${payment-service.url}") String paymentServiceUrl) {
        this.jwtService = jwtService;
        this.restClient = RestClient.builder()
                .baseUrl(paymentServiceUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(Duration.ofSeconds(3))
                                .withReadTimeout(Duration.ofSeconds(5))))
                .build();
    }

    /**
     * Soft-delete every active payment belonging to {@code userId} in
     * payment-service. Failures are logged and swallowed — see class javadoc.
     *
     * <p>Propagates the current request's {@code X-Request-Id} (read from the
     * MDC, set by {@link RequestIdFilter} on the way in) to payment-service,
     * so the cascade-delete call keeps the trace continuous across both
     * services. If there is no current requestId (e.g. call not triggered by
     * an HTTP request), the header is simply omitted.</p>
     */
    public void deleteAllPaymentsForUser(UUID userId) {
        try {
            restClient.delete()
                    .uri("/payments/by-user/{userId}", userId)
                    .headers(headers -> {
                        headers.setBearerAuth(jwtService.generateServiceToken());
                        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                        if (requestId != null) {
                            headers.add(RequestIdFilter.REQUEST_ID_HEADER, requestId);
                        }
                    })
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            // Reconciliation debt assumed: no retry, no queue, no saga. The user
            // is already soft-deleted (this runs after that transaction commits);
            // if this call fails, their payments are orphaned until a manual/future
            // reconciliation pass. Logged loudly on purpose so it's visible in ops.
            log.error("Cascade delete of payments failed for user {}: {}", userId, ex.getMessage(), ex);
        }
    }
}
