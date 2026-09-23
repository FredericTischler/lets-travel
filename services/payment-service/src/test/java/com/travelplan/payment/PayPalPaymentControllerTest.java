package com.travelplan.payment;

import com.travelplan.payment.controller.PayPalPaymentController;
import com.travelplan.payment.dto.CreatePayPalPaymentRequest;
import com.travelplan.payment.dto.PayPalPaymentResponse;
import com.travelplan.payment.dto.PaymentResponse;
import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;
import com.travelplan.payment.service.PayPalPaymentService;
import com.travelplan.payment.service.TokenValidationService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link PayPalPaymentController#capture}, mocking both
 * collaborators — no Spring context needed, RBAC is enforced by hand via
 * {@link TokenValidationService} rather than a filter chain (see that
 * class's javadoc), so a plain POJO call exercises the same wiring MockMvc
 * would. See {@link PayPalPaymentServiceTest} for why this endpoint has no
 * automated coverage otherwise in this CI environment.
 */
@ExtendWith(MockitoExtension.class)
class PayPalPaymentControllerTest {

    @Mock
    private PayPalPaymentService payPalPaymentService;

    @Mock
    private TokenValidationService tokenValidationService;

    private PayPalPaymentController controller;

    @BeforeEach
    void setUp() {
        controller = new PayPalPaymentController(payPalPaymentService, tokenValidationService);
    }

    @Test
    void capture_validatesTokenAndPassesTheCallersIdentityToTheService() {
        String orderId = "ORDER-1";
        Payment payment = new Payment(UUID.randomUUID(), new BigDecimal("19.99"), "USD",
                PaymentProvider.PAYPAL, orderId);
        payment.setStatus(Payment.STATUS_COMPLETED);
        PaymentResponse response = PaymentResponse.from(payment);
        UUID caller = UUID.randomUUID();
        Claims claims = mock(Claims.class);
        when(tokenValidationService.requireAnyRole("Bearer valid-token")).thenReturn(claims);
        when(tokenValidationService.callerId(claims)).thenReturn(caller);
        when(tokenValidationService.isAdmin(claims)).thenReturn(false);
        when(payPalPaymentService.captureOrder(orderId, caller, false)).thenReturn(response);

        var result = controller.capture(orderId, "Bearer valid-token");

        verify(tokenValidationService).requireAnyRole("Bearer valid-token");
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isSameAs(response);
    }

    @Test
    void create_validatesTokenAndOwnership_thenReturns201WithTheCreatedOrder() {
        UUID userId = UUID.randomUUID();
        CreatePayPalPaymentRequest request = new CreatePayPalPaymentRequest();
        request.setUserId(userId);
        request.setAmount(new BigDecimal("19.99"));
        request.setCurrency("USD");
        Payment payment = new Payment(userId, new BigDecimal("19.99"), "USD",
                PaymentProvider.PAYPAL, "ORDER-1");
        PayPalPaymentResponse response = PayPalPaymentResponse.from(payment, "https://paypal.example/approve");
        Claims claims = mock(Claims.class);
        when(tokenValidationService.requireAnyRole("Bearer valid-token")).thenReturn(claims);
        when(payPalPaymentService.createOrder(request)).thenReturn(response);

        var result = controller.create(request, "Bearer valid-token");

        verify(tokenValidationService).requireAnyRole("Bearer valid-token");
        verify(tokenValidationService).requireOwnerOrAdmin(claims, userId);
        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(result.getBody()).isSameAs(response);
    }
}
