package com.travelplan.payment;

import com.travelplan.payment.dto.CreateManualPaymentRequest;
import com.travelplan.payment.dto.CreatePayPalPaymentRequest;
import com.travelplan.payment.dto.CreateStripePaymentRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit tests for the three "create a payment" request DTOs
 * ({@link CreateManualPaymentRequest}, {@link CreatePayPalPaymentRequest},
 * {@link CreateStripePaymentRequest}) and the getters/setters they inherit
 * from {@code CreatePaymentRequest}/{@code SubscriptionLink}.
 *
 * <p>These fields are otherwise only exercised indirectly through Stripe/
 * PayPal sandbox integration tests that self-skip in this environment (no
 * real provider test credentials — see {@code StripePaymentIntegrationTest}/
 * {@code PayPalPaymentIntegrationTest}), so a plain construct-and-assert
 * test is the cheapest way to cover this pure boilerplate directly, no
 * Spring context needed.</p>
 */
class CreatePaymentRequestTest {

    private static Stream<Object> requests() {
        return Stream.of(
                new CreateManualPaymentRequest(),
                new CreatePayPalPaymentRequest(),
                new CreateStripePaymentRequest());
    }

    @Test
    void manualRequest_exposesEveryFieldItWasGiven() {
        CreateManualPaymentRequest request = new CreateManualPaymentRequest();
        UUID userId = UUID.randomUUID();
        request.setUserId(userId);
        request.setAmount(new BigDecimal("42.50"));
        request.setCurrency("EUR");

        assertThat(request.getUserId()).isEqualTo(userId);
        assertThat(request.getAmount()).isEqualByComparingTo("42.50");
        assertThat(request.getCurrency()).isEqualTo("EUR");
        assertThat(request.isSubscriptionLinked()).isFalse();
        assertThat(request.isLinkConsistent()).isTrue();
    }

    @Test
    void payPalRequest_exposesEveryFieldItWasGiven() {
        CreatePayPalPaymentRequest request = new CreatePayPalPaymentRequest();
        UUID userId = UUID.randomUUID();
        request.setUserId(userId);
        request.setAmount(new BigDecimal("19.99"));
        request.setCurrency("USD");

        assertThat(request.getUserId()).isEqualTo(userId);
        assertThat(request.getAmount()).isEqualByComparingTo("19.99");
        assertThat(request.getCurrency()).isEqualTo("USD");
    }

    @Test
    void stripeRequest_exposesEveryFieldItWasGiven() {
        CreateStripePaymentRequest request = new CreateStripePaymentRequest();
        UUID userId = UUID.randomUUID();
        request.setUserId(userId);
        request.setAmount(new BigDecimal("7.00"));
        request.setCurrency("GBP");

        assertThat(request.getUserId()).isEqualTo(userId);
        assertThat(request.getAmount()).isEqualByComparingTo("7.00");
        assertThat(request.getCurrency()).isEqualTo("GBP");
    }

    @Test
    void subscriptionLink_isTrueOnlyWhenBothFieldsArePresent() {
        CreatePayPalPaymentRequest request = new CreatePayPalPaymentRequest();
        UUID travelId = UUID.randomUUID();
        UUID subscriptionRef = UUID.randomUUID();

        assertThat(request.isSubscriptionLinked()).isFalse();
        assertThat(request.isLinkConsistent()).isTrue();

        request.setTravelId(travelId);
        assertThat(request.isSubscriptionLinked()).isFalse();
        assertThat(request.isLinkConsistent()).isFalse();

        request.setSubscriptionRef(subscriptionRef);
        assertThat(request.isSubscriptionLinked()).isTrue();
        assertThat(request.isLinkConsistent()).isTrue();
        assertThat(request.getTravelId()).isEqualTo(travelId);
        assertThat(request.getSubscriptionRef()).isEqualTo(subscriptionRef);
    }

    @Test
    void everyCreateRequestSubtype_isInstantiableWithNoArgConstructor() {
        assertThat(requests()).isNotEmpty().allSatisfy(request -> assertThat(request).isNotNull());
    }
}
