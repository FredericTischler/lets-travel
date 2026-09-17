package com.travelplan.payment.service;

import com.travelplan.payment.dto.CreateManualPaymentRequest;
import com.travelplan.payment.dto.PaymentResponse;
import com.travelplan.payment.dto.UpdateStatusRequest;
import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.exception.InvalidStatusValueException;
import com.travelplan.payment.exception.PaymentAlreadyTerminalException;
import com.travelplan.payment.exception.PaymentNotFoundException;
import com.travelplan.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
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

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
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
        Payment saved = paymentRepository.save(payment);
        return PaymentResponse.from(saved);
    }

    /**
     * Find an active payment by id.
     *
     * @throws PaymentNotFoundException if the payment does not exist or is soft-deleted
     */
    public PaymentResponse findById(UUID id) {
        Payment payment = paymentRepository.findActiveById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
        return PaymentResponse.from(payment);
    }

    /**
     * Return all active payments.
     */
    public List<PaymentResponse> findAll() {
        return paymentRepository.findAllActive().stream()
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
        return PaymentResponse.from(payment);
    }

    /**
     * Soft-delete an active payment (sets deleted_at = now()).
     *
     * @throws PaymentNotFoundException if the payment does not exist or is already soft-deleted
     */
    @Transactional
    public void delete(UUID id) {
        Payment payment = paymentRepository.findActiveById(id)
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