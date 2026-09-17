package com.travelplan.payment.config;

import com.paypal.sdk.Environment;
import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.authentication.ClientCredentialsAuthModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the PayPal Server SDK client used by
 * {@link com.travelplan.payment.service.PayPalPaymentService}.
 *
 * <p><b>Sandbox environment only:</b> {@link Environment#SANDBOX} is
 * hardcoded — no production PayPal environment is ever used by this
 * increment.</p>
 *
 * <p>Building the client does not itself perform any network call: the
 * client-credentials OAuth exchange with PayPal only happens lazily, on the
 * first actual API call (e.g. {@code OrdersController.createOrder}). A
 * missing/invalid {@code PAYPAL_CLIENT_ID}/{@code PAYPAL_CLIENT_SECRET}
 * therefore only surfaces as a {@link com.travelplan.payment.exception.PaymentProviderException}
 * at call time, not at startup — startup only fails if the env vars
 * themselves are absent (see {@code paypal.*} in application.yml).</p>
 */
@Configuration
public class PayPalConfig {

    @Bean
    public PaypalServerSdkClient paypalServerSdkClient(
            @Value("${paypal.client-id}") String clientId,
            @Value("${paypal.client-secret}") String clientSecret) {
        return new PaypalServerSdkClient.Builder()
                .clientCredentialsAuth(new ClientCredentialsAuthModel.Builder(clientId, clientSecret).build())
                .environment(Environment.SANDBOX)
                .build();
    }
}
