package com.travelplan.payment.service;

import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.http.response.ApiResponse;
import io.apimatic.core.exceptions.AuthValidationException;
import com.paypal.sdk.models.AmountWithBreakdown;
import com.paypal.sdk.models.CaptureOrderInput;
import com.paypal.sdk.models.CheckoutPaymentIntent;
import com.paypal.sdk.models.CreateOrderInput;
import com.paypal.sdk.models.LinkDescription;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderRequest;
import com.paypal.sdk.models.OrderStatus;
import com.paypal.sdk.models.PurchaseUnitRequest;
import com.travelplan.payment.dto.CreatePayPalPaymentRequest;
import com.travelplan.payment.dto.PayPalPaymentResponse;
import com.travelplan.payment.dto.PaymentResponse;
import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;
import com.travelplan.payment.exception.PaymentAlreadyTerminalException;
import com.travelplan.payment.exception.PaymentNotFoundException;
import com.travelplan.payment.exception.PaymentProviderException;
import com.travelplan.payment.repository.PaymentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Creates PayPal Orders and captures them after payer approval
 * (docs/sujet.md §2 — PayPal support).
 *
 * <p><b>Scope delivered — creation:</b> creating a PayPal Order (intent
 * {@code CAPTURE}) and persisting a {@code PENDING} payment row with its id
 * (standard PayPal Orders v2 "create order" step). The response includes the
 * order's PayPal-hosted approval URL — the client is expected to redirect
 * the payer there to approve the order themselves.</p>
 *
 * <p><b>Scope delivered — capture:</b> {@link #captureOrder} calls PayPal's
 * Orders v2 capture endpoint for an order the payer has already approved
 * (standard "explicit capture after client-side redirect" pattern: the
 * client calls this after PayPal redirects the payer back to the app's
 * return URL) and transitions the corresponding {@code Payment} row
 * (looked up by {@code externalReference} = the Order id) to
 * {@code COMPLETED} on success or {@code FAILED} if PayPal's capture call
 * itself fails.</p>
 *
 * <p><b>Still a simplified flow vs. full production PayPal integration:</b>
 * PayPal's own guidance is to also verify completion via the
 * {@code PAYMENT.CAPTURE.COMPLETED} webhook, since an explicit client-driven
 * capture call can be interrupted (network failure, browser closed) before
 * the server ever learns the outcome — a webhook is the only fully reliable
 * signal. No PayPal webhook is implemented in this increment (see
 * {@code docs/sujet.md} for whether a mirror of the Stripe webhook was
 * added). There is also no PayPal-side idempotency key
 * ({@code PayPal-Request-Id}) applied to the capture call and no retry
 * logic beyond what a single synchronous call provides.</p>
 */
@Service
public class PayPalPaymentService {

    private static final Set<String> TERMINAL_STATUSES =
            Set.of(Payment.STATUS_COMPLETED, Payment.STATUS_FAILED);

    private final PaymentRepository paymentRepository;
    private final PaypalServerSdkClient paypalServerSdkClient;
    private final ApplicationEventPublisher eventPublisher;

    public PayPalPaymentService(PaymentRepository paymentRepository, PaypalServerSdkClient paypalServerSdkClient,
                                ApplicationEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.paypalServerSdkClient = paypalServerSdkClient;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Create a PayPal Order for the given amount/currency and persist a new
     * {@code PENDING} payment with {@code provider=PAYPAL} and
     * {@code externalReference} set to the Order id.
     *
     * @throws PaymentProviderException if the call to PayPal's API fails
     */
    @Transactional
    public PayPalPaymentResponse createOrder(CreatePayPalPaymentRequest request) {
        AmountWithBreakdown amount = new AmountWithBreakdown.Builder(
                request.getCurrency(),
                formatAmount(request.getAmount(), request.getCurrency()))
                .build();
        PurchaseUnitRequest purchaseUnit = new PurchaseUnitRequest.Builder(amount).build();
        OrderRequest orderRequest = new OrderRequest.Builder(CheckoutPaymentIntent.CAPTURE, List.of(purchaseUnit))
                .build();
        CreateOrderInput input = new CreateOrderInput.Builder("application/json", orderRequest).build();

        Order order;
        try {
            ApiResponse<Order> response = paypalServerSdkClient.getOrdersController().createOrder(input);
            order = response.getResult();
        } catch (ApiException | IOException | AuthValidationException ex) {
            // AuthValidationException is thrown by the SDK's own client-side
            // credential check (io.apimatic ClientCredentialsAuth), BEFORE any
            // network call — invalid/placeholder PAYPAL_CLIENT_ID/SECRET land
            // here, not in ApiException. Without this branch it escapes as an
            // unhandled 500 instead of the intended 502 PaymentProviderException.
            throw new PaymentProviderException("PayPal", ex);
        }

        String approveUrl = order.getLinks() == null ? null : order.getLinks().stream()
                .filter(link -> "approve".equals(link.getRel()))
                .map(LinkDescription::getHref)
                .findFirst()
                .orElse(null);

        Payment payment = new Payment(request.getUserId(), request.getAmount(), request.getCurrency(),
                PaymentProvider.PAYPAL, order.getId());
        if (request.isSubscriptionLinked()) {
            payment.linkToSubscription(request.getTravelId(), request.getSubscriptionRef());
        }
        Payment saved = paymentRepository.save(payment);
        return PayPalPaymentResponse.from(saved, approveUrl);
    }

    /**
     * Capture a previously-created, payer-approved PayPal Order.
     *
     * <p>Deliberately NOT wrapped in a single {@code @Transactional} method:
     * on a provider-side capture failure, the {@code FAILED} status must
     * still be persisted even though a {@link PaymentProviderException} is
     * then thrown to surface the failure as a 502 to the caller — an
     * enclosing transaction would roll that status change back along with
     * the exception. Each {@link PaymentRepository} call below already runs
     * in its own transaction (standard Spring Data JPA repository
     * behaviour), so the status change from the catch block below survives.</p>
     *
     * <p>Ownership (security audit G2): the payment is resolved by its order id
     * and must belong to {@code callerId} unless {@code isAdmin}. This check runs
     * BEFORE any call to PayPal, and a non-owner gets the very same
     * {@link PaymentNotFoundException} as for an unknown order — same masking as
     * {@link PaymentService#findById}, so an order id's existence is not leaked.</p>
     *
     * @throws PaymentNotFoundException if no active payment has this order id
     *     as its {@code externalReference}, or it belongs to someone else
     *     (and the caller is not an admin)
     * @throws PaymentAlreadyTerminalException if that payment's status is
     *     already COMPLETED or FAILED
     * @throws PaymentProviderException if the call to PayPal's capture API
     *     fails (the payment is still transitioned to FAILED before this is thrown)
     */
    public PaymentResponse captureOrder(String orderId, UUID callerId, boolean isAdmin) {
        Payment payment = paymentRepository.findActiveByExternalReference(orderId)
                .filter(p -> isAdmin || p.getUserId().equals(callerId))
                .orElseThrow(() -> new PaymentNotFoundException(orderId));

        if (TERMINAL_STATUSES.contains(payment.getStatus())) {
            throw new PaymentAlreadyTerminalException(payment.getId(), payment.getStatus());
        }

        CaptureOrderInput input = new CaptureOrderInput.Builder(orderId, "application/json").build();
        Order order;
        try {
            ApiResponse<Order> response = paypalServerSdkClient.getOrdersController().captureOrder(input);
            order = response.getResult();
        } catch (ApiException | IOException | AuthValidationException ex) {
            // Same three exception types as createOrder above — see that
            // catch block's comment on AuthValidationException.
            payment.setStatus(Payment.STATUS_FAILED);
            paymentRepository.save(payment);
            // No enclosing transaction here, so the notifier runs right away (see
            // SubscriptionPaymentNotifier) — the FAILED status is already committed.
            eventPublisher.publishEvent(new PaymentStatusChanged(payment.getId()));
            throw new PaymentProviderException("PayPal", ex);
        }

        payment.setStatus(order.getStatus() == OrderStatus.COMPLETED
                ? Payment.STATUS_COMPLETED
                : Payment.STATUS_FAILED);
        Payment saved = paymentRepository.save(payment);
        eventPublisher.publishEvent(new PaymentStatusChanged(saved.getId()));
        return PaymentResponse.from(saved);
    }

    /**
     * Formats a decimal amount with the exact number of decimal places
     * PayPal's Orders API requires for the given currency (0 for JPY, 3 for
     * BHD, 2 for most others — ISO 4217 via {@link Currency}).
     *
     * <p>Previously hardcoded to 2 decimals for every currency: PayPal
     * validates decimal precision server-side and rejects a mismatched
     * value (e.g. "1000.00" for JPY), so every zero/three-decimal-currency
     * order failed with a 502 — not documented anywhere before this fix.</p>
     *
     * @throws PaymentProviderException if the currency code isn't a valid
     *     ISO 4217 code the JVM recognizes
     */
    private static String formatAmount(BigDecimal amount, String currencyCode) {
        int fractionDigits;
        try {
            fractionDigits = Currency.getInstance(currencyCode.toUpperCase()).getDefaultFractionDigits();
        } catch (IllegalArgumentException ex) {
            throw new PaymentProviderException("PayPal", ex);
        }
        if (fractionDigits < 0) {
            // Pseudo-currencies (e.g. XXX) report -1 fraction digits; PayPal
            // doesn't support them, fail fast instead of guessing a scale.
            throw new PaymentProviderException("PayPal",
                    new IllegalArgumentException("Currency " + currencyCode + " has no defined minor unit"));
        }
        return amount.setScale(fractionDigits, RoundingMode.HALF_UP).toPlainString();
    }
}
