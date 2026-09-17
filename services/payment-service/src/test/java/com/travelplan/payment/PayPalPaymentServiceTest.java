package com.travelplan.payment;

import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.controllers.OrdersController;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderStatus;
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

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

    private PayPalPaymentService payPalPaymentService;

    @BeforeEach
    void setUp() {
        payPalPaymentService = new PayPalPaymentService(paymentRepository, paypalServerSdkClient);
    }

    private Payment pendingPayment(String orderId) {
        return new Payment(UUID.randomUUID(), new BigDecimal("19.99"), "USD",
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

        var response = payPalPaymentService.captureOrder(orderId);

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

        var response = payPalPaymentService.captureOrder(orderId);

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

        assertThatThrownBy(() -> payPalPaymentService.captureOrder(orderId))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(payment.getStatus()).isEqualTo(Payment.STATUS_FAILED);
        verify(paymentRepository).save(payment);
    }

    @Test
    void captureOrder_throwsNotFound_whenNoActivePaymentHasThisExternalReference() {
        String orderId = "ORDER-UNKNOWN";
        when(paymentRepository.findActiveByExternalReference(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> payPalPaymentService.captureOrder(orderId))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void captureOrder_throwsAlreadyTerminal_whenPaymentIsAlreadyCompleted() {
        String orderId = "ORDER-4";
        Payment payment = pendingPayment(orderId);
        payment.setStatus(Payment.STATUS_COMPLETED);
        when(paymentRepository.findActiveByExternalReference(orderId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> payPalPaymentService.captureOrder(orderId))
                .isInstanceOf(PaymentAlreadyTerminalException.class);
    }
}
