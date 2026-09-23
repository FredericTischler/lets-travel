package com.travelplan.payment;

import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.travelplan.payment.dto.CreateStripePaymentRequest;
import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.exception.PaymentProviderException;
import com.travelplan.payment.repository.PaymentRepository;
import com.travelplan.payment.service.StripePaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link StripePaymentService#createPaymentIntent}, mocking the
 * static {@link PaymentIntent#create(PaymentIntentCreateParams)} call (no
 * real Stripe sandbox key, no network call).
 *
 * <p>{@link StripePaymentIntegrationTest} exercises the real Stripe test-mode
 * API end-to-end but SKIPS ITSELF in this CI environment (no
 * {@code STRIPE_TEST_SECRET_KEY}), leaving {@code createPaymentIntent}
 * entirely unexercised by the pipeline. This test fills that gap, mirroring
 * the pattern {@link PayPalPaymentServiceTest} already uses for the
 * equivalent PayPal gap.</p>
 */
@ExtendWith(MockitoExtension.class)
class StripePaymentServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final String WEBHOOK_SECRET = "whsec_test_only_not_a_real_secret";

    private PaymentRepository paymentRepository;
    private ApplicationEventPublisher eventPublisher;
    private StripePaymentService stripePaymentService;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        stripePaymentService = new StripePaymentService(paymentRepository, eventPublisher, WEBHOOK_SECRET);
    }

    private CreateStripePaymentRequest request(BigDecimal amount, String currency) {
        CreateStripePaymentRequest request = new CreateStripePaymentRequest();
        request.setUserId(OWNER);
        request.setAmount(amount);
        request.setCurrency(currency);
        return request;
    }

    @Test
    void createPaymentIntent_persistsAPendingPayment_andReturnsTheClientSecret() throws Exception {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = new PaymentIntent();
        intent.setId("pi_test_123");
        intent.setClientSecret("pi_test_123_secret_abc");

        try (MockedStatic<PaymentIntent> mockedStatic = Mockito.mockStatic(PaymentIntent.class)) {
            mockedStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenReturn(intent);

            var response = stripePaymentService.createPaymentIntent(request(new BigDecimal("19.99"), "USD"));

            assertThat(response.getPaymentIntentId()).isEqualTo("pi_test_123");
            assertThat(response.getClientSecret()).isEqualTo("pi_test_123_secret_abc");
            assertThat(response.getStatus()).isEqualTo(Payment.STATUS_PENDING);
            assertThat(response.getUserId()).isEqualTo(OWNER);
            assertThat(response.getId()).isNull();
            assertThat(response.getAmount()).isEqualByComparingTo("19.99");
            assertThat(response.getProvider()).isEqualTo(com.travelplan.payment.entity.PaymentProvider.STRIPE);
            assertThat(response.getCreatedAt()).isNotNull();
        }
    }

    @Test
    void createPaymentIntent_linksTheSubscription_whenBothTravelIdAndSubscriptionRefArePresent() throws Exception {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = new PaymentIntent();
        intent.setId("pi_test_456");
        intent.setClientSecret("pi_test_456_secret");
        CreateStripePaymentRequest req = request(new BigDecimal("9.00"), "USD");
        UUID travelId = UUID.randomUUID();
        UUID subscriptionRef = UUID.randomUUID();
        req.setTravelId(travelId);
        req.setSubscriptionRef(subscriptionRef);

        try (MockedStatic<PaymentIntent> mockedStatic = Mockito.mockStatic(PaymentIntent.class)) {
            mockedStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenReturn(intent);

            var response = stripePaymentService.createPaymentIntent(req);

            assertThat(response.getTravelId()).isEqualTo(travelId);
            assertThat(response.getSubscriptionRef()).isEqualTo(subscriptionRef);
        }
    }

    @Test
    void createPaymentIntent_throwsPaymentProviderException_whenStripeApiCallFails() {
        StripeException stripeException =
                new CardException("card declined", "req_1", "card_declined", null, null, null, null, null);

        try (MockedStatic<PaymentIntent> mockedStatic = Mockito.mockStatic(PaymentIntent.class)) {
            mockedStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(stripeException);

            assertThatThrownBy(() -> stripePaymentService.createPaymentIntent(
                    request(new BigDecimal("19.99"), "USD")))
                    .isInstanceOf(PaymentProviderException.class);
        }
    }

    @Test
    void createPaymentIntent_throwsPaymentProviderException_forAPseudoCurrencyWithNoMinorUnit() {
        assertThatThrownBy(() -> stripePaymentService.createPaymentIntent(
                request(new BigDecimal("10.00"), "XXX")))
                .isInstanceOf(PaymentProviderException.class);
    }

    @Test
    void createPaymentIntent_throwsPaymentProviderException_forAnUnrecognizedCurrencyCode() {
        assertThatThrownBy(() -> stripePaymentService.createPaymentIntent(
                request(new BigDecimal("10.00"), "ZZZ")))
                .isInstanceOf(PaymentProviderException.class);
    }

    @Test
    void createPaymentIntent_handlesAZeroDecimalCurrency() throws Exception {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = new PaymentIntent();
        intent.setId("pi_test_jpy");
        intent.setClientSecret("pi_test_jpy_secret");

        try (MockedStatic<PaymentIntent> mockedStatic = Mockito.mockStatic(PaymentIntent.class)) {
            mockedStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenReturn(intent);

            var response = stripePaymentService.createPaymentIntent(request(new BigDecimal("1000"), "JPY"));

            assertThat(response.getCurrency()).isEqualTo("JPY");
        }
    }
}
