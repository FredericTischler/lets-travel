package com.travelplan.payment;

import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.controllers.OrdersController;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.LinkDescription;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderStatus;
import com.travelplan.payment.dto.CreatePayPalPaymentRequest;
import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;
import com.travelplan.payment.exception.PaymentAlreadyTerminalException;
import com.travelplan.payment.exception.PaymentNotFoundException;
import com.travelplan.payment.exception.PaymentProviderException;
import com.travelplan.payment.repository.PaymentRepository;
import com.travelplan.payment.service.PayPalPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link PayPalPaymentService#captureOrder}, mocking the
 * PayPal SDK client (no real sandbox credentials, no Testcontainers).
 *
 * <p>{@link PayPalCaptureIntegrationTest} exercises the real PayPal sandbox
 * end-to-end but SKIPS ITSELF in this CI environment (no
 * {@code PAYPAL_TEST_CLIENT_ID}/{@code PAYPAL_TEST_CLIENT_SECRET}), leaving
 * {@code captureOrder} entirely unexercised by the pipeline. This test fills
 * that gap with the PayPal SDK mocked, so every branch of {@code
 * captureOrder} runs on every build regardless of external credentials.</p>
 */
@ExtendWith(MockitoExtension.class)
class PayPalPaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaypalServerSdkClient paypalServerSdkClient;

    @Mock
    private OrdersController ordersController;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PayPalPaymentService payPalPaymentService;

    @BeforeEach
    void setUp() {
        payPalPaymentService = new PayPalPaymentService(paymentRepository, paypalServerSdkClient, eventPublisher);
    }

    private static final UUID OWNER = UUID.randomUUID();

    private Payment pendingPayment(String orderId) {
        return new Payment(OWNER, new BigDecimal("19.99"), "USD",
                PaymentProvider.PAYPAL, orderId);
    }

    @Test
    void captureOrder_marksPaymentCompleted_whenPayPalReportsCompleted() throws Exception {
        String orderId = "ORDER-1";
        Payment payment = pendingPayment(orderId);
        when(paymentRepository.findActiveByExternalReference(orderId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paypalServerSdkClient.getOrdersController()).thenReturn(ordersController);
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(OrderStatus.COMPLETED);
        when(ordersController.captureOrder(any())).thenReturn(new ApiResponse<>(201, null, order));

        var response = payPalPaymentService.captureOrder(orderId, OWNER, false);

        assertThat(response.getStatus()).isEqualTo(Payment.STATUS_COMPLETED);
        assertThat(payment.getStatus()).isEqualTo(Payment.STATUS_COMPLETED);
    }

    @Test
    void captureOrder_marksPaymentFailed_whenPayPalReportsNonCompletedStatus() throws Exception {
        String orderId = "ORDER-2";
        Payment payment = pendingPayment(orderId);
        when(paymentRepository.findActiveByExternalReference(orderId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paypalServerSdkClient.getOrdersController()).thenReturn(ordersController);
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(OrderStatus.APPROVED);
        when(ordersController.captureOrder(any())).thenReturn(new ApiResponse<>(200, null, order));

        var response = payPalPaymentService.captureOrder(orderId, OWNER, false);

        assertThat(response.getStatus()).isEqualTo(Payment.STATUS_FAILED);
    }

    @Test
    void captureOrder_marksPaymentFailedAndThrows_whenPayPalApiCallFails() throws Exception {
        String orderId = "ORDER-3";
        Payment payment = pendingPayment(orderId);
        when(paymentRepository.findActiveByExternalReference(orderId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paypalServerSdkClient.getOrdersController()).thenReturn(ordersController);
        when(ordersController.captureOrder(any())).thenThrow(new ApiException("PayPal rejected the capture"));

        assertThatThrownBy(() -> payPalPaymentService.captureOrder(orderId, OWNER, false))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(payment.getStatus()).isEqualTo(Payment.STATUS_FAILED);
        verify(paymentRepository).save(payment);
    }

    @Test
    void captureOrder_throwsNotFound_whenNoActivePaymentHasThisExternalReference() {
        String orderId = "ORDER-UNKNOWN";
        when(paymentRepository.findActiveByExternalReference(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> payPalPaymentService.captureOrder(orderId, OWNER, false))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void captureOrder_throwsAlreadyTerminal_whenPaymentIsAlreadyCompleted() {
        String orderId = "ORDER-4";
        Payment payment = pendingPayment(orderId);
        payment.setStatus(Payment.STATUS_COMPLETED);
        when(paymentRepository.findActiveByExternalReference(orderId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> payPalPaymentService.captureOrder(orderId, OWNER, false))
                .isInstanceOf(PaymentAlreadyTerminalException.class);
    }

    @Test
    void captureOrder_masksAnotherUsersPaymentAsNotFound_andNeverCallsPayPal() {
        String orderId = "ORDER-5";
        Payment payment = pendingPayment(orderId);
        when(paymentRepository.findActiveByExternalReference(orderId)).thenReturn(Optional.of(payment));
        UUID otherUser = UUID.randomUUID();

        assertThatThrownBy(() -> payPalPaymentService.captureOrder(orderId, otherUser, false))
                .isInstanceOf(PaymentNotFoundException.class);

        verifyNoInteractions(paypalServerSdkClient);
        verify(paymentRepository, never()).save(any());
        assertThat(payment.getStatus()).isEqualTo(Payment.STATUS_PENDING);
    }

    @Test
    void captureOrder_lets_anAdminCaptureAnyonesPayment() throws Exception {
        String orderId = "ORDER-6";
        Payment payment = pendingPayment(orderId);
        when(paymentRepository.findActiveByExternalReference(orderId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paypalServerSdkClient.getOrdersController()).thenReturn(ordersController);
        Order order = new Order();
        order.setStatus(OrderStatus.COMPLETED);
        when(ordersController.captureOrder(any())).thenReturn(new ApiResponse<>(201, null, order));

        var response = payPalPaymentService.captureOrder(orderId, UUID.randomUUID(), true);

        assertThat(response.getStatus()).isEqualTo(Payment.STATUS_COMPLETED);
    }

    private CreatePayPalPaymentRequest createRequest(BigDecimal amount, String currency) {
        CreatePayPalPaymentRequest request = new CreatePayPalPaymentRequest();
        request.setUserId(OWNER);
        request.setAmount(amount);
        request.setCurrency(currency);
        return request;
    }

    @Test
    void createOrder_persistsAPendingPayment_andReturnsTheApproveUrl() throws Exception {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paypalServerSdkClient.getOrdersController()).thenReturn(ordersController);
        Order order = new Order();
        order.setId("ORDER-CREATE-1");
        order.setLinks(List.of(
                new LinkDescription.Builder("https://paypal.example/cancel", "cancel").build(),
                new LinkDescription.Builder("https://paypal.example/approve", "approve").build()));
        when(ordersController.createOrder(any())).thenReturn(new ApiResponse<>(201, null, order));

        var response = payPalPaymentService.createOrder(createRequest(new BigDecimal("19.99"), "USD"));

        assertThat(response.getOrderId()).isEqualTo("ORDER-CREATE-1");
        assertThat(response.getApproveUrl()).isEqualTo("https://paypal.example/approve");
        assertThat(response.getStatus()).isEqualTo(Payment.STATUS_PENDING);
        assertThat(response.getUserId()).isEqualTo(OWNER);
        assertThat(response.getId()).isNull();
        assertThat(response.getAmount()).isEqualByComparingTo("19.99");
        assertThat(response.getProvider()).isEqualTo(PaymentProvider.PAYPAL);
        assertThat(response.getCreatedAt()).isNotNull();
    }

    @Test
    void createOrder_returnsNullApproveUrl_whenPayPalDidNotIncludeOne() throws Exception {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paypalServerSdkClient.getOrdersController()).thenReturn(ordersController);
        Order order = new Order();
        order.setId("ORDER-CREATE-2");
        order.setLinks(null);
        when(ordersController.createOrder(any())).thenReturn(new ApiResponse<>(201, null, order));

        var response = payPalPaymentService.createOrder(createRequest(new BigDecimal("19.99"), "USD"));

        assertThat(response.getApproveUrl()).isNull();
    }

    @Test
    void createOrder_linksTheSubscription_whenBothTravelIdAndSubscriptionRefArePresent() throws Exception {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paypalServerSdkClient.getOrdersController()).thenReturn(ordersController);
        Order order = new Order();
        order.setId("ORDER-CREATE-3");
        order.setLinks(List.of(new LinkDescription.Builder("https://paypal.example/approve", "approve").build()));
        when(ordersController.createOrder(any())).thenReturn(new ApiResponse<>(201, null, order));
        CreatePayPalPaymentRequest request = createRequest(new BigDecimal("9.00"), "USD");
        UUID travelId = UUID.randomUUID();
        UUID subscriptionRef = UUID.randomUUID();
        request.setTravelId(travelId);
        request.setSubscriptionRef(subscriptionRef);

        var response = payPalPaymentService.createOrder(request);

        assertThat(response.getTravelId()).isEqualTo(travelId);
        assertThat(response.getSubscriptionRef()).isEqualTo(subscriptionRef);
    }

    @Test
    void createOrder_throwsPaymentProviderException_whenPayPalApiCallFails() throws Exception {
        when(paypalServerSdkClient.getOrdersController()).thenReturn(ordersController);
        when(ordersController.createOrder(any())).thenThrow(new ApiException("PayPal rejected the order"));

        CreatePayPalPaymentRequest request = createRequest(new BigDecimal("19.99"), "USD");
        assertThatThrownBy(() -> payPalPaymentService.createOrder(request))
                .isInstanceOf(PaymentProviderException.class);
    }

    @Test
    void createOrder_throwsPaymentProviderException_forAPseudoCurrencyWithNoMinorUnit() {
        CreatePayPalPaymentRequest request = createRequest(new BigDecimal("10.00"), "XXX");
        assertThatThrownBy(() -> payPalPaymentService.createOrder(request))
                .isInstanceOf(PaymentProviderException.class);

        verifyNoInteractions(paypalServerSdkClient);
    }

    @Test
    void createOrder_throwsPaymentProviderException_forAnUnrecognizedCurrencyCode() {
        CreatePayPalPaymentRequest request = createRequest(new BigDecimal("10.00"), "ZZZ");
        assertThatThrownBy(() -> payPalPaymentService.createOrder(request))
                .isInstanceOf(PaymentProviderException.class);

        verifyNoInteractions(paypalServerSdkClient);
    }

    @Test
    void createOrder_handlesAZeroDecimalCurrency() throws Exception {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paypalServerSdkClient.getOrdersController()).thenReturn(ordersController);
        Order order = new Order();
        order.setId("ORDER-CREATE-JPY");
        order.setLinks(List.of(new LinkDescription.Builder("https://paypal.example/approve", "approve").build()));
        when(ordersController.createOrder(any())).thenReturn(new ApiResponse<>(201, null, order));

        var response = payPalPaymentService.createOrder(createRequest(new BigDecimal("1000"), "JPY"));

        assertThat(response.getCurrency()).isEqualTo("JPY");
    }
}
