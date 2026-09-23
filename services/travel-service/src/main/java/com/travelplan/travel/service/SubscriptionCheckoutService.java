package com.travelplan.travel.service;

import com.travelplan.travel.dto.PaymentCheckoutResponse;
import com.travelplan.travel.dto.PaymentProvider;
import com.travelplan.travel.dto.SubscribeRequest;
import com.travelplan.travel.dto.SubscriptionResponse;
import com.travelplan.travel.entity.Destination;
import com.travelplan.travel.exception.DestinationNotFoundException;
import com.travelplan.travel.exception.InvalidSubscriptionRequestException;
import com.travelplan.travel.exception.PaymentUnavailableException;
import com.travelplan.travel.exception.SubscriptionConflictException;
import com.travelplan.travel.repository.DestinationRepository;
import com.travelplan.travel.repository.SubscriptionRepository;
import com.travelplan.travel.repository.SubscriptionRepository.NewSubscription;
import com.travelplan.travel.repository.SubscriptionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The subscribe flow (docs/lets-travel-architecture-decisions.md §3 and, for a
 * priced destination, §4 addendum): create the {@code SUBSCRIBED} relation and,
 * when the destination costs something, have payment-service create the
 * payment that will later activate it. The rest of the subscription logic
 * (cancel, list, payment outcome) lives in {@link SubscriptionService}.
 *
 * <p><b>Deliberately has no {@code @Transactional} at all</b> — not even
 * {@code NOT_SUPPORTED}, and that is why this is a class of its own rather
 * than a method of {@link SubscriptionService}. The paid path makes an HTTP
 * call to payment-service between two writes and must be able to compensate
 * (cancel the pending subscription) when that call fails, so every Cypher
 * statement has to commit on its own. Spring Data Neo4j's {@code Neo4jClient}
 * only runs a statement in autocommit when no Spring transaction
 * <i>synchronization</i> is active; a {@code @Transactional} method — even
 * {@code NOT_SUPPORTED} — starts one, and {@code Neo4jClient} then binds a
 * transaction to it that the exception rolls back, silently undoing the
 * pending subscription <i>and</i> its compensation. (Found by a test: a
 * failed payment call left no trace of the cancelled attempt.)</p>
 *
 * <p>Repository calls made here that are themselves transactional (SDN
 * repositories) each run and commit in their own short transaction.</p>
 */
@Service
public class SubscriptionCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionCheckoutService.class);

    /** {@code Destination} carries a price but no currency: paid subscriptions default to this one. */
    static final String DEFAULT_CURRENCY = "EUR";

    private final SubscriptionRepository subscriptionRepository;
    private final DestinationRepository destinationRepository;
    private final PaymentServiceClient paymentServiceClient;
    private final Duration pendingTtl;
    private final Duration pendingTtlManual;

    /**
     * @param pendingTtlMinutes       how long a Stripe/PayPal payment may stay unpaid before
     *                                its seat is released (interactive checkout: short)
     * @param pendingTtlManualMinutes same for a MANUAL payment, which an administrator
     *                                confirms out of band (bank transfer, cash): days, not minutes
     */
    public SubscriptionCheckoutService(SubscriptionRepository subscriptionRepository,
                                        DestinationRepository destinationRepository,
                                        PaymentServiceClient paymentServiceClient,
                                        @Value("${subscription.pending-ttl-minutes}") long pendingTtlMinutes,
                                        @Value("${subscription.pending-ttl-manual-minutes}") long pendingTtlManualMinutes) {
        this.subscriptionRepository = subscriptionRepository;
        this.destinationRepository = destinationRepository;
        this.paymentServiceClient = paymentServiceClient;
        this.pendingTtl = Duration.ofMinutes(pendingTtlMinutes);
        this.pendingTtlManual = Duration.ofMinutes(pendingTtlManualMinutes);
    }

    /**
     * Subscribe {@code travelerId} to {@code destinationId}.
     *
     * <p>Free destination (price null/0): created {@code ACTIVE}, {@code request}
     * is ignored. Paid destination: {@code request.provider} is required; the
     * subscription is created {@code PENDING_PAYMENT} (holding a seat until
     * {@code expiresAt}), then the payment is created in payment-service with
     * the traveler's own token. If that call fails the pending subscription is
     * cancelled again (compensation) and a 502 is raised — the traveler can
     * retry. If it succeeds, the payment id is stored best-effort on the
     * subscription (informational; the payment callback finds the subscription
     * by its own id, not through that field).</p>
     *
     * @param request              may be {@code null} (free destinations need no body)
     * @param authorizationHeader  the caller's own {@code Authorization} header, forwarded to
     *                             payment-service for a paid destination
     * @throws DestinationNotFoundException if the destination does not exist or is soft-deleted
     * @throws InvalidSubscriptionRequestException if the destination is paid and no provider was chosen
     * @throws SubscriptionConflictException if the destination's startDate has already passed,
     *         the traveler already holds a live subscription, or no seat is left
     * @throws PaymentUnavailableException if payment-service could not create the payment
     */
    public SubscriptionResponse subscribe(UUID destinationId, UUID travelerId, SubscribeRequest request,
                                           String authorizationHeader) {
        Destination destination = destinationRepository.findActiveById(destinationId)
                .orElseThrow(() -> new DestinationNotFoundException(destinationId));
        if (destination.getStartDate() != null && destination.getStartDate().isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw SubscriptionConflictException.travelAlreadyStarted(destinationId);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (subscriptionRepository.findLiveStatus(travelerId, destinationId, now).isPresent()) {
            throw SubscriptionConflictException.alreadySubscribed(travelerId, destinationId);
        }

        UUID subscriptionId = UUID.randomUUID();
        if (!isPaid(destination)) {
            SubscriptionView created = subscriptionRepository
                    .subscribe(new NewSubscription(subscriptionId, travelerId, destinationId,
                            SubscriptionService.STATUS_ACTIVE, null, null, null), now)
                    .orElseThrow(() -> SubscriptionConflictException.capacityReached(destinationId));
            return SubscriptionResponse.from(created);
        }

        PaymentProvider provider = request == null ? null : request.getProvider();
        if (provider == null) {
            throw InvalidSubscriptionRequestException.providerRequired();
        }
        String currency = request.getCurrency() != null ? request.getCurrency() : DEFAULT_CURRENCY;
        BigDecimal amount = destination.getPrice();
        Duration ttl = provider == PaymentProvider.MANUAL ? pendingTtlManual : pendingTtl;

        SubscriptionView pending = subscriptionRepository
                .subscribe(new NewSubscription(subscriptionId, travelerId, destinationId,
                        SubscriptionService.STATUS_PENDING_PAYMENT, now.plus(ttl), amount, currency), now)
                .orElseThrow(() -> SubscriptionConflictException.capacityReached(destinationId));

        PaymentCheckoutResponse checkout;
        try {
            checkout = paymentServiceClient.createPayment(
                    authorizationHeader, provider, travelerId, amount, currency, destinationId, subscriptionId);
        } catch (PaymentUnavailableException ex) {
            // Compensation: no payment exists, so nothing could ever confirm this hold — free the seat now.
            subscriptionRepository.cancelPending(subscriptionId, null, OffsetDateTime.now(ZoneOffset.UTC));
            throw ex;
        }
        try {
            subscriptionRepository.attachPayment(subscriptionId, checkout.paymentId(), provider.name());
        } catch (RuntimeException ex) {
            // Informational link only: the payment exists and will find the subscription by its id.
            log.warn("Could not record payment {} on subscription {}: {}",
                    checkout.paymentId(), subscriptionId, ex.getMessage());
        }
        return SubscriptionResponse.from(new SubscriptionView(
                pending.destinationId(), pending.travelerId(), pending.status(), pending.subscribedAt(),
                pending.cancelledAt(), pending.id(), pending.expiresAt(), checkout.paymentId(),
                pending.amount(), pending.currency()), checkout);
    }

    /** A destination is paid when it has a price strictly above zero; null or 0 means free. */
    private static boolean isPaid(Destination destination) {
        return destination.getPrice() != null && destination.getPrice().compareTo(BigDecimal.ZERO) > 0;
    }
}
