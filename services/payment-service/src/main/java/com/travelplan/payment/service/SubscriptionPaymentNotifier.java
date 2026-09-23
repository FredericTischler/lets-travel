package com.travelplan.payment.service;

import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pushes the terminal status of a subscription-linked payment to
 * travel-service (docs/lets-travel-architecture-decisions.md §4 addendum).
 *
 * <p><b>When:</b> right after the transaction that moved the payment to
 * {@code COMPLETED}/{@code FAILED} has committed
 * ({@link TransactionalEventListener} {@code AFTER_COMMIT}; when the status
 * change ran without a transaction — the PayPal capture path — the listener
 * runs immediately, {@code fallbackExecution}). Notifying before commit would
 * let travel-service act on a status that could still be rolled back.</p>
 *
 * <p><b>Failure window (assumed, not fixed):</b> the payment is committed
 * {@code COMPLETED} but the HTTP call fails. There is no distributed
 * transaction: the failure is logged, {@code travel_notified_at} stays
 * {@code NULL}, and {@link #reconcile()} — exposed to admins as
 * {@code POST /payments/reconcile-subscriptions} — re-sends every such
 * payment. travel-service's endpoint is idempotent, so re-sending is safe. A
 * scheduler could call {@code reconcile()} later; none is wired here.</p>
 *
 * <p>Payments without a {@code subscription_ref} are ignored entirely.</p>
 */
@Component
public class SubscriptionPaymentNotifier {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPaymentNotifier.class);

    private static final Set<String> TERMINAL_STATUSES =
            Set.of(Payment.STATUS_COMPLETED, Payment.STATUS_FAILED);

    private final PaymentRepository paymentRepository;
    private final TravelServiceClient travelServiceClient;

    public SubscriptionPaymentNotifier(PaymentRepository paymentRepository,
                                       TravelServiceClient travelServiceClient) {
        this.paymentRepository = paymentRepository;
        this.travelServiceClient = travelServiceClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPaymentStatusChanged(PaymentStatusChanged event) {
        // Deliberately swallow everything: this runs after the payment's own
        // transaction is committed, nothing here may fail the caller (a Stripe
        // webhook answered 5xx would make Stripe redeliver for no reason).
        try {
            paymentRepository.findActiveById(event.paymentId()).ifPresent(this::notifyTravelService);
        } catch (RuntimeException ex) {
            log.error("Unexpected failure confirming payment {} to travel-service", event.paymentId(), ex);
        }
    }

    /**
     * Re-send every terminal, subscription-linked payment travel-service has
     * not acknowledged yet.
     *
     * @return {@code {attempted, notified}}
     */
    public ReconciliationResult reconcile() {
        List<Payment> pending = paymentRepository.findTerminalAwaitingTravelNotification();
        int notified = 0;
        for (Payment payment : pending) {
            if (notifyTravelService(payment)) {
                notified++;
            }
        }
        return new ReconciliationResult(pending.size(), notified);
    }

    private boolean notifyTravelService(Payment payment) {
        if (payment.getSubscriptionRef() == null
                || payment.getTravelNotifiedAt() != null
                || !TERMINAL_STATUSES.contains(payment.getStatus())) {
            return false;
        }
        boolean acknowledged = travelServiceClient.sendPaymentResult(payment);
        if (acknowledged) {
            markNotified(payment.getId());
        }
        return acknowledged;
    }

    private void markNotified(UUID paymentId) {
        paymentRepository.markTravelNotified(paymentId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    public record ReconciliationResult(int attempted, int notified) {
    }
}
