package com.travelplan.payment.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Initializes the Stripe SDK's global API key at startup.
 *
 * <p><b>Sandbox/test mode only:</b> {@code stripe.secret-key} must be a
 * {@code sk_test_*} key (see docker-compose.payment.yml / Vault
 * secret/payment/stripe, wired in a separate infra task) — no live key is
 * ever used by this increment.</p>
 *
 * <p><b>{@code stripe.api-key} is intentionally unused here:</b> it is bound
 * (see application.yml) purely to fail fast if absent, for parity with the
 * two credentials already reserved in Vault. Only {@code secret-key}
 * authenticates server-side Stripe API calls (creating a PaymentIntent is a
 * secret-key operation); a publishable/client key is only ever needed by
 * client-side Stripe.js, which is out of scope for this backend service.</p>
 */
@Configuration
public class StripeConfig {

    @Value("${stripe.api-key}")
    private String apiKey; // validated present, not otherwise used server-side — see class javadoc

    @Value("${stripe.secret-key}")
    private String secretKey;

    @PostConstruct
    void initStripeApiKey() {
        Stripe.apiKey = secretKey;
    }
}
