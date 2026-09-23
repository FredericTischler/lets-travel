export const environment = {
  production: true,
  identityApiUrl: 'https://identity.localhost',
  paymentApiUrl: 'https://payment.localhost',
  travelApiUrl: 'https://travel.localhost',
  // Stripe *publishable* key: unlike the secret key (Vault, payment-service
  // only), this one is meant to be public and safe to ship in the bundle —
  // it only lets the browser create a card form and confirm a PaymentIntent
  // it was already given a client secret for. Empty until a real sandbox
  // account exists for this project; the Stripe step of the payment screen
  // degrades to an explicit "not configured" message when it is empty
  // instead of trying to load Stripe.js.
  stripePublishableKey: '',
};