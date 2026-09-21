package com.travelplan.travel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelplan.travel.dto.PaymentCheckoutResponse;
import com.travelplan.travel.dto.PaymentProvider;
import com.travelplan.travel.exception.PaymentUnavailableException;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Creates the payment of a paid subscription in payment-service
 * (docs/lets-travel-architecture-decisions.md §4 addendum) by calling the
 * existing create endpoint of the traveler's chosen provider —
 * {@code POST /payments}, {@code /payments/stripe} or {@code /payments/paypal}.
 * Nothing of Stripe/PayPal is reimplemented here.
 *
 * <p>Same pattern as identity-service's {@code PaymentServiceClient}
 * (RestClient, short timeouts, {@code X-Request-Id} propagated from the MDC),
 * with one deliberate difference in auth: the call carries the <b>traveler's
 * own bearer token</b>, not a service token. payment-service therefore
 * enforces, exactly as for a direct call, that the payment's {@code userId}
 * is the caller — a traveler can only ever pay for their own subscription,
 * and travel-service cannot be tricked into creating a payment for someone
 * else. (The reverse direction — payment-service reporting the outcome —
 * has no user in the loop, so it uses a service token.)</p>
 *
 * <p>Unlike identity's cascade call this one is <b>not</b> best-effort-and-swallow:
 * the caller needs the payment to exist to hand the traveler something to pay,
 * so any failure is surfaced as {@link PaymentUnavailableException} (502)
 * after the caller has cancelled the pending subscription. No retry.</p>
 */
@Component
public class PaymentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceClient.class);

    private final RestClient restClient;

    public PaymentServiceClient(@Value("${payment-service.url}") String paymentServiceUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(paymentServiceUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(Duration.ofSeconds(3))
                                // Stripe/PayPal creation makes payment-service call the provider: allow for it.
                                .withReadTimeout(Duration.ofSeconds(15))))
                .build();
    }

    /**
     * Create a payment of {@code amount} {@code currency} for {@code userId}
     * (always the caller), linked to the given subscription.
     *
     * @param authorizationHeader the traveler's own {@code Authorization} header, forwarded as-is
     * @throws PaymentUnavailableException if payment-service is unreachable, times out or answers non-2xx
     */
    public PaymentCheckoutResponse createPayment(String authorizationHeader, PaymentProvider provider,
                                                 UUID userId, BigDecimal amount, String currency,
                                                 UUID travelId, UUID subscriptionRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("travelId", travelId);
        body.put("subscriptionRef", subscriptionRef);
        try {
            JsonNode created = restClient.post()
                    .uri(provider.createPath())
                    .headers(headers -> {
                        headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
                        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                        if (requestId != null) {
                            headers.add(RequestIdFilter.REQUEST_ID_HEADER, requestId);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (created == null || !created.hasNonNull("id")) {
                throw new PaymentUnavailableException("payment-service returned no payment id", null);
            }
            return new PaymentCheckoutResponse(
                    UUID.fromString(created.get("id").asText()),
                    provider,
                    created.path("status").asText(null),
                    created.hasNonNull("clientSecret") ? created.get("clientSecret").asText() : null,
                    created.hasNonNull("approveUrl") ? created.get("approveUrl").asText() : null);
        } catch (RestClientException ex) {
            log.error("payment-service could not create the {} payment for subscription {}: {}",
                    provider, subscriptionRef, ex.getMessage());
            throw new PaymentUnavailableException("The payment could not be created, please retry", ex);
        }
    }
}
