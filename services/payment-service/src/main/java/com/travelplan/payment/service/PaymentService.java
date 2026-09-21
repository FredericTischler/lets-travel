package com.travelplan.payment.service;

import com.travelplan.payment.dto.CreateManualPaymentRequest;
import com.travelplan.payment.dto.PaymentResponse;
import com.travelplan.payment.dto.UpdateStatusRequest;
import com.travelplan.payment.dto.PaymentSummaryResponse;
import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;
import com.travelplan.payment.exception.InvalidStatusValueException;
import com.travelplan.payment.exception.PaymentAlreadyTerminalException;
import com.travelplan.payment.exception.PaymentNotFoundException;
import com.travelplan.payment.repository.PaymentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for payment lifecycle management.
 *
 * Rules enforced here:
 * - A manually-created payment is always created with status PENDING; the
 *   client cannot influence this at creation time (no status field on
 *   {@link CreateManualPaymentRequest}).
 * - Status transitions only ever move PENDING -> COMPLETED or PENDING -> FAILED.
 *   PENDING is never a valid transition target. Once a payment is COMPLETED
 *   or FAILED, its status is immutable — no further transition is permitted,
 *   even to the same value or to the other terminal value.
 * - "Delete" always means soft-delete: deleted_at is set to now(), the row
 *   stays. Soft-delete is independent from status: a COMPLETED payment can
 *   still be soft-deleted.
 * - findById / findAll silently filter out soft-deleted rows (callers receive
 *   a 404 / empty list, not a soft-deleted row).
 */
@Service
@Transactional(readOnly = true)
public class PaymentService {

    private static final Set<String> ALLOWED_TARGET_STATUSES = Set.of(
            Payment.STATUS_COMPLETED, Payment.STATUS_FAILED);

    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentService(PaymentRepository paymentRepository, ApplicationEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Create a new manual payment. Status is always forced to PENDING.
     *
     * {@code request.getUserId()} is stored as-is, with no existence check
     * against identity-service: payment-service has no access to
     * identity_db, so this ownership reference is a trust boundary assumed
     * at the application layer, not enforced here.
     */
    @Transactional
    public PaymentResponse create(CreateManualPaymentRequest request) {
        Payment payment = new Payment(request.getUserId(), request.getAmount(), request.getCurrency());
        if (request.isSubscriptionLinked()) {
            payment.linkToSubscription(request.getTravelId(), request.getSubscriptionRef());
        }
        Payment saved = paymentRepository.save(payment);
        return PaymentResponse.from(saved);
    }

    /**
     * Find an active payment by id, visible only to its owner or an admin.
     *
     * A non-owner, non-admin caller gets the exact same 404 as a genuinely
     * absent payment (never a 403) — this must not leak whether the id
     * exists at all, see docs/lets-travel-architecture-decisions.md §1.
     *
     * @throws PaymentNotFoundException if the payment does not exist, is
     *         soft-deleted, or does not belong to {@code callerId} (unless {@code isAdmin})
     */
    public PaymentResponse findById(UUID id, UUID callerId, boolean isAdmin) {
        Payment payment = paymentRepository.findActiveById(id)
                .filter(p -> isAdmin || p.getUserId().equals(callerId))
                .orElseThrow(() -> new PaymentNotFoundException(id));
        return PaymentResponse.from(payment);
    }

    /**
     * Return all active payments: every one of them for an admin, only the
     * caller's own otherwise (see docs/lets-travel-architecture-decisions.md §1).
     */
    public List<PaymentResponse> findAll(UUID callerId, boolean isAdmin) {
        List<Payment> payments = isAdmin
                ? paymentRepository.findAllActive()
                : paymentRepository.findAllActiveByUserId(callerId);
        return payments.stream()
                .map(PaymentResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Transition a payment's status to COMPLETED or FAILED.
     *
     * @throws PaymentNotFoundException        if the payment does not exist or is soft-deleted
     * @throws InvalidStatusValueException     if the requested target value is not COMPLETED or FAILED
     * @throws PaymentAlreadyTerminalException  if the payment's current status is already terminal
     */
    @Transactional
    public PaymentResponse updateStatus(UUID id, UpdateStatusRequest request) {
        Payment payment = paymentRepository.findActiveById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        if (!ALLOWED_TARGET_STATUSES.contains(request.getStatus())) {
            throw new InvalidStatusValueException(request.getStatus());
        }

        if (ALLOWED_TARGET_STATUSES.contains(payment.getStatus())) {
            throw new PaymentAlreadyTerminalException(id, payment.getStatus());
        }

        payment.setStatus(request.getStatus());
        // the dirty check within the transaction persists the change automatically
        // Tells travel-service (only for a subscription-linked payment) once this commits —
        // see SubscriptionPaymentNotifier.
        eventPublisher.publishEvent(new PaymentStatusChanged(payment.getId()));
        return PaymentResponse.from(payment);
    }

    /**
     * Aggregate of {@code userId}'s {@code COMPLETED} payments: count and total
     * per provider (the subject's "preferred payment methods" traveler stat),
     * plus the most-used provider.
     *
     * <p>Only {@code COMPLETED} payments count — a {@code PENDING} or
     * {@code FAILED} attempt is not a method the traveler actually paid with.
     * Totals are kept per currency (summing EUR and USD would be meaningless).
     * "Most used" = highest count; a tie is broken by provider name only so
     * the answer is deterministic (amounts in different currencies are not
     * comparable, so the total is deliberately not a tie-breaker).</p>
     */
    public PaymentSummaryResponse summarize(UUID userId) {
        Map<PaymentProvider, Long> counts = new EnumMap<>(PaymentProvider.class);
        Map<PaymentProvider, Map<String, BigDecimal>> totals = new EnumMap<>(PaymentProvider.class);
        for (Object[] row : paymentRepository.summarizeCompletedByUser(userId)) {
            PaymentProvider provider = (PaymentProvider) row[0];
            counts.merge(provider, (Long) row[2], Long::sum);
            totals.computeIfAbsent(provider, k -> new TreeMap<>()).put((String) row[1], (BigDecimal) row[3]);
        }
        List<PaymentSummaryResponse.ProviderSummary> byProvider = counts.entrySet().stream()
                .map(e -> new PaymentSummaryResponse.ProviderSummary(
                        e.getKey(), e.getValue(), totals.get(e.getKey())))
                .sorted(Comparator
                        .comparingLong(PaymentSummaryResponse.ProviderSummary::count).reversed()
                        .thenComparing(s -> s.provider().name()))
                .toList();
        long totalCount = byProvider.stream().mapToLong(PaymentSummaryResponse.ProviderSummary::count).sum();
        PaymentProvider mostUsed = byProvider.isEmpty() ? null : byProvider.get(0).provider();
        return new PaymentSummaryResponse(userId, totalCount, byProvider, mostUsed);
    }

    /**
     * Soft-delete an active payment (sets deleted_at = now()), visible only
     * to its owner or an admin — same masking rule as {@link #findById}.
     *
     * @throws PaymentNotFoundException if the payment does not exist, is
     *         already soft-deleted, or does not belong to {@code callerId} (unless {@code isAdmin})
     */
    @Transactional
    public void delete(UUID id, UUID callerId, boolean isAdmin) {
        Payment payment = paymentRepository.findActiveById(id)
                .filter(p -> isAdmin || p.getUserId().equals(callerId))
                .orElseThrow(() -> new PaymentNotFoundException(id));
        payment.setDeletedAt(OffsetDateTime.now());
        // the dirty check within the transaction persists the change automatically
    }

    /**
     * Soft-delete every active payment belonging to the given user.
     *
     * Unlike {@link #delete(UUID)}, an empty result is not an error: a user
     * with zero active payments simply yields a count of 0, no exception.
     *
     * @return the number of payments soft-deleted
     */
    @Transactional
    public int deleteAllByUserId(UUID userId) {
        List<Payment> activePayments = paymentRepository.findAllActiveByUserId(userId);
        OffsetDateTime now = OffsetDateTime.now();
        for (Payment payment : activePayments) {
            payment.setDeletedAt(now);
        }
        // the dirty check within the transaction persists each change automatically
        return activePayments.size();
    }
}