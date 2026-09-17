package com.travelplan.payment.service;

import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.travelplan.payment.dto.CreateStripePaymentRequest;
import com.travelplan.payment.dto.StripePaymentResponse;
import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;
import com.travelplan.payment.exception.InvalidWebhookSignatureException;
import com.travelplan.payment.exception.PaymentProviderException;
import com.travelplan.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Set;

/**
 * Creates Stripe PaymentIntents and reconciles them via webhook
 * (docs/sujet.md §2 — Stripe support).
 *
 * <p><b>Scope delivered — creation:</b> creating the PaymentIntent and
 * persisting a {@code PENDING} payment row with its id (standard Stripe
 * "PaymentIntent creation" step of the flow). The client is expected to
 * confirm the PaymentIntent itself (Stripe.js/Elements) using the returned
 * {@code client_secret} — that confirmation step happens entirely on
 * Stripe's side and on the client, not in this service.</p>
 *
 * <p><b>Scope delivered — reconciliation:</b> {@link #handleWebhookEvent}
 * verifies and processes the {@code payment_intent.succeeded} /
 * {@code payment_intent.payment_failed} events Stripe sends to
 * {@code POST /webhooks/stripe}, transitioning the corresponding
 * {@code Payment} row (looked up by {@code externalReference} = the
 * PaymentIntent id) to {@code COMPLETED}/{@code FAILED} automatically.
 * Any other event type is accepted and ignored (Stripe's webhook endpoint
 * contract expects a 2xx for event types it doesn't care about, not an
 * error). A payment already in a terminal status is left untouched — Stripe
 * may redeliver the same event, and this makes reconciliation idempotent.
 * If no {@code Payment} row matches the PaymentIntent id (e.g. a stale or
 * foreign event), the event is likewise accepted and ignored rather than
 * rejected, so Stripe does not retry indefinitely for something this
 * service will never be able to resolve.</p>
 *
 * <p><b>Still simplified vs. a full production integration:</b> no
 * idempotency-key tracking of already-processed event ids (Stripe
 * recommends deduplicating by {@code event.id} for exactly-once semantics;
 * here, redelivery is merely harmless, not deduplicated), and no retry/dead
 * letter handling beyond what returning a non-2xx causes Stripe itself to
 * do.</p>
 */
@Service
public class StripePaymentService {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentService.class);

    private static final Set<String> TERMINAL_STATUSES =
            Set.of(Payment.STATUS_COMPLETED, Payment.STATUS_FAILED);

    private final PaymentRepository paymentRepository;
    private final String webhookSecret;

    public StripePaymentService(PaymentRepository paymentRepository,
                                 @Value("${stripe.webhook-secret}") String webhookSecret) {
        this.paymentRepository = paymentRepository;
        this.webhookSecret = webhookSecret;
    }

    /**
     * Create a Stripe PaymentIntent for the given amount/currency and persist
     * a new {@code PENDING} payment with {@code provider=STRIPE} and
     * {@code externalReference} set to the PaymentIntent id.
     *
     * @throws PaymentProviderException if the call to Stripe's API fails
     */
    @Transactional
    public StripePaymentResponse createPaymentIntent(CreateStripePaymentRequest request) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toSmallestCurrencyUnit(request.getAmount(), request.getCurrency()))
                .setCurrency(request.getCurrency().toLowerCase())
                .build();

        PaymentIntent paymentIntent;
        try {
            paymentIntent = PaymentIntent.create(params);
        } catch (StripeException ex) {
            throw new PaymentProviderException("Stripe", ex);
        }

        Payment payment = new Payment(request.getUserId(), request.getAmount(), request.getCurrency(),
                PaymentProvider.STRIPE, paymentIntent.getId());
        Payment saved = paymentRepository.save(payment);
        return StripePaymentResponse.from(saved, paymentIntent.getClientSecret());
    }

    /**
     * Verify and process a raw {@code POST /webhooks/stripe} request body
     * from Stripe.
     *
     * <p>Verifies {@code sigHeader} against {@code payload} using this
     * service's {@code STRIPE_WEBHOOK_SECRET} (via {@code Webhook.constructEvent},
     * stripe-java's own signature-verification utility) — this is the sole
     * authenticity check for this endpoint; it intentionally does not go
     * through {@link TokenValidationService}, since Stripe is the caller,
     * not an identity-service-authenticated user.</p>
     *
     * <p>On {@code payment_intent.succeeded} / {@code payment_intent.payment_failed},
     * looks up the {@link Payment} by {@code externalReference} = the
     * PaymentIntent id and transitions its status to
     * {@code COMPLETED}/{@code FAILED} respectively — unless it is already
     * terminal (idempotent against Stripe's at-least-once redelivery) or no
     * matching payment exists (silently ignored — see class javadoc). Every
     * other event type is accepted and ignored.</p>
     *
     * @throws InvalidWebhookSignatureException if signature verification fails
     */
    @Transactional
    public void handleWebhookEvent(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException ex) {
            throw new InvalidWebhookSignatureException("Stripe", ex);
        }

        String type = event.getType();
        if (!"payment_intent.succeeded".equals(type) && !"payment_intent.payment_failed".equals(type)) {
            return;
        }

        String paymentIntentId = extractPaymentIntentId(event);
        if (paymentIntentId == null) {
            return;
        }

        var existing = paymentRepository.findActiveByExternalReference(paymentIntentId);
        if (existing.isEmpty()) {
            // Not necessarily a bug (a stale/foreign event) but indistinguishable
            // from one without this line: without it, a broken lookup for ALL
            // payments would look identical to normal "unknown reference" noise.
            log.warn("Stripe webhook {}: no active payment found for externalReference={}", type, paymentIntentId);
            return;
        }

        Payment payment = existing.get();
        if (TERMINAL_STATUSES.contains(payment.getStatus())) {
            return;
        }
        payment.setStatus("payment_intent.succeeded".equals(type)
                ? Payment.STATUS_COMPLETED
                : Payment.STATUS_FAILED);
        // the dirty check within the transaction persists the change automatically
    }

    /**
     * Extracts the PaymentIntent id from a {@code payment_intent.*} event's
     * data object, tolerating an event API version that doesn't match this
     * library's pinned {@code Stripe.API_VERSION} (the safe
     * {@link com.stripe.model.EventDataObjectDeserializer#getObject()} path
     * returns empty in that case — falls back to
     * {@code deserializeUnsafe()}, which is fine here since only the {@code id}
     * field is read).
     */
    private static String extractPaymentIntentId(Event event) {
        StripeObject dataObject = event.getDataObjectDeserializer().getObject().orElse(null);
        if (dataObject == null) {
            try {
                dataObject = event.getDataObjectDeserializer().deserializeUnsafe();
            } catch (EventDataObjectDeserializationException ex) {
                return null;
            }
        }
        if (dataObject instanceof PaymentIntent paymentIntent) {
            return paymentIntent.getId();
        }
        return null;
    }

    /**
     * Converts a decimal amount to the integer count of the currency's
     * smallest unit that Stripe's API expects (e.g. 12.34 USD -> 1234 cents,
     * 1000 JPY -> 1000, 12.345 BHD -> 12345).
     *
     * <p>Uses {@link Currency#getDefaultFractionDigits()} (ISO 4217) rather
     * than assuming 2 decimals for every currency — a fixed x100 previously
     * silently produced a charge 100x too large for zero-decimal currencies
     * like JPY instead of erroring.</p>
     *
     * @throws PaymentProviderException if the currency code isn't a valid
     *     ISO 4217 code Stripe/the JVM recognizes
     */
    private static long toSmallestCurrencyUnit(BigDecimal amount, String currencyCode) {
        int fractionDigits;
        try {
            fractionDigits = Currency.getInstance(currencyCode.toUpperCase()).getDefaultFractionDigits();
        } catch (IllegalArgumentException ex) {
            throw new PaymentProviderException("Stripe", ex);
        }
        if (fractionDigits < 0) {
            // Pseudo-currencies (e.g. XXX) report -1 fraction digits; Stripe
            // doesn't support them, fail fast instead of guessing a scale.
            throw new PaymentProviderException("Stripe",
                    new IllegalArgumentException("Currency " + currencyCode + " has no defined minor unit"));
        }
        return amount.setScale(fractionDigits, RoundingMode.HALF_UP)
                .movePointRight(fractionDigits)
                .longValueExact();
    }
}
