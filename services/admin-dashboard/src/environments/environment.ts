export const environment = {
  production: true,
  identityApiUrl: 'https://identity.localhost',
  paymentApiUrl: 'https://payment.localhost',
  travelApiUrl: 'https://travel.localhost',
  // Stripe.js publishable key (pk_...). Empty by default — no real Stripe key exists in
  // this project (placeholder, not a secret): the card form is only shown when this is set.
  stripePublishableKey: '',
};