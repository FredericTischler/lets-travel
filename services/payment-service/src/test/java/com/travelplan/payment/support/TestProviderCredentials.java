package com.travelplan.payment.support;

/**
 * Dummy Stripe/PayPal credentials shared by every {@code @SpringBootTest} in
 * this module, registered via {@code @DynamicPropertySource} purely to
 * satisfy the fail-fast {@code stripe.*}/{@code paypal.*} placeholders in
 * application.yml (see {@link com.travelplan.payment.config.StripeConfig},
 * {@link com.travelplan.payment.config.PayPalConfig}).
 *
 * <p>These values are syntactically plausible (Stripe test-key prefix,
 * arbitrary PayPal sandbox-looking id/secret) but are NOT valid credentials
 * for either provider. That is safe here: building the Stripe/PayPal SDK
 * clients at startup performs no network call (Stripe just stores the key
 * statically; PayPal's OAuth exchange is lazy, on first real API call — see
 * PayPalConfig javadoc). Only a test that actually invokes
 * {@code StripePaymentService}/{@code PayPalPaymentService} needs REAL
 * sandbox credentials — see {@code StripePaymentIntegrationTest} and
 * {@code PayPalPaymentIntegrationTest}, which skip themselves when real
 * credentials are not supplied via environment variables.</p>
 */
public final class TestProviderCredentials {

    public static final String STRIPE_API_KEY = "pk_test_dummy_not_a_real_key";
    public static final String STRIPE_SECRET_KEY = "sk_test_dummy_not_a_real_key";
    public static final String STRIPE_WEBHOOK_SECRET = "whsec_test_dummy_secret_for_payment_tests";
    public static final String PAYPAL_CLIENT_ID = "dummy-paypal-client-id";
    public static final String PAYPAL_CLIENT_SECRET = "dummy-paypal-client-secret";

    private TestProviderCredentials() {
    }
}
