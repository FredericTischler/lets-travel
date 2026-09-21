package com.travelplan.payment.service;

import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.filter.RequestIdFilter;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tells travel-service the outcome of a subscription-linked payment by calling
 * {@code POST /internal/subscriptions/{subscriptionRef}/payment-result}
 * (docs/lets-travel-architecture-decisions.md §4 addendum) — the mirror image
 * of identity-service's {@code PaymentServiceClient}, same pattern: RestClient
 * with short timeouts, short-lived service-to-service token, {@code X-Request-Id}
 * propagated from the MDC, failures reported as a {@code false} return value
 * instead of an exception.
 *
 * <p><b>Best-effort, no retry here:</b> a failed call (travel-service down,
 * timeout, non-2xx) is logged and returned as {@code false}; the payment stays
 * with {@code travel_notified_at = NULL}, which is what
 * {@link SubscriptionPaymentNotifier#reconcile()} later picks up. No queue, no
 * distributed transaction.</p>
 */
@Component
public class TravelServiceClient {

    private static final Logger log = LoggerFactory.getLogger(TravelServiceClient.class);

    private final RestClient restClient;
    private final JwtService jwtService;

    public TravelServiceClient(JwtService jwtService,
                               @Value("${travel-service.url}") String travelServiceUrl) {
        this.jwtService = jwtService;
        this.restClient = RestClient.builder()
                .baseUrl(travelServiceUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(Duration.ofSeconds(3))
                                .withReadTimeout(Duration.ofSeconds(5))))
                .build();
    }

    /**
     * Report {@code payment}'s terminal status to travel-service.
     *
     * @return {@code true} iff travel-service answered 2xx (it applied the
     *         result, or had already applied it — the endpoint is idempotent)
     */
    public boolean sendPaymentResult(Payment payment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("travelId", payment.getTravelId());
        body.put("userId", payment.getUserId());
        body.put("paymentId", payment.getId());
        body.put("status", payment.getStatus());
        body.put("amount", payment.getAmount());
        body.put("currency", payment.getCurrency());
        try {
            restClient.post()
                    .uri("/internal/subscriptions/{ref}/payment-result", payment.getSubscriptionRef())
                    .headers(headers -> {
                        headers.setBearerAuth(jwtService.generateServiceToken());
                        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                        if (requestId != null) {
                            headers.add(RequestIdFilter.REQUEST_ID_HEADER, requestId);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException ex) {
            // Reconciliation debt assumed: the payment keeps travel_notified_at = NULL and is
            // re-sent by POST /payments/reconcile-subscriptions. Logged loudly on purpose.
            log.error("Could not confirm payment {} ({}) to travel-service for subscription {}: {}",
                    payment.getId(), payment.getStatus(), payment.getSubscriptionRef(), ex.getMessage());
            return false;
        }
    }
}
