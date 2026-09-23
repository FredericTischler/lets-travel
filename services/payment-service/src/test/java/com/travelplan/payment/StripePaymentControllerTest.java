package com.travelplan.payment;

import com.travelplan.payment.controller.StripePaymentController;
import com.travelplan.payment.dto.CreateStripePaymentRequest;
import com.travelplan.payment.dto.StripePaymentResponse;
import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;
import com.travelplan.payment.service.StripePaymentService;
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
 * Unit test for {@link StripePaymentController#create}, mocking both
 * collaborators — no Spring context needed, RBAC is enforced by hand via
 * {@link TokenValidationService} rather than a filter chain (see that
 * class's javadoc), so a plain POJO call exercises the same wiring MockMvc
 * would. Mirrors {@link PayPalPaymentControllerTest}: {@link StripePaymentIntegrationTest}
 * exercises the real Stripe test-mode API but SKIPS ITSELF in this CI
 * environment (no {@code STRIPE_TEST_SECRET_KEY}), leaving this endpoint
 * otherwise unexercised by the pipeline.
 */
@ExtendWith(MockitoExtension.class)
class StripePaymentControllerTest {

    @Mock
    private StripePaymentService stripePaymentService;

    @Mock
    private TokenValidationService tokenValidationService;

    private StripePaymentController controller;

    @BeforeEach
    void setUp() {
        controller = new StripePaymentController(stripePaymentService, tokenValidationService);
    }

    @Test
    void create_validatesTokenAndOwnership_thenReturns201WithTheCreatedIntent() {
        UUID userId = UUID.randomUUID();
        CreateStripePaymentRequest request = new CreateStripePaymentRequest();
        request.setUserId(userId);
        request.setAmount(new BigDecimal("19.99"));
        request.setCurrency("USD");
        Payment payment = new Payment(userId, new BigDecimal("19.99"), "USD",
                PaymentProvider.STRIPE, "pi_test_123");
        StripePaymentResponse response = StripePaymentResponse.from(payment, "pi_test_123_secret");
        Claims claims = mock(Claims.class);
        when(tokenValidationService.requireAnyRole("Bearer valid-token")).thenReturn(claims);
        when(stripePaymentService.createPaymentIntent(request)).thenReturn(response);

        var result = controller.create(request, "Bearer valid-token");

        verify(tokenValidationService).requireAnyRole("Bearer valid-token");
        verify(tokenValidationService).requireOwnerOrAdmin(claims, userId);
        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(result.getBody()).isSameAs(response);
    }
}
