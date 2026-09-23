// En `ng serve`, le navigateur ne parle qu'à http://localhost:4200 : le dev-server relaie
// ces préfixes vers la gateway Traefik (voir proxy.conf.json). Évite d'avoir à faire
// accepter le certificat auto-signé de Traefik pour chaque hôte *.localhost.
export const environment = {
  production: false,
  identityApiUrl: '/api/identity',
  paymentApiUrl: '/api/payment',
  travelApiUrl: '/api/travel',
  // Set to a real `pk_test_...` (matching payment-service's STRIPE_SECRET_KEY)
  // to exercise the card form locally; see environment.ts for why this is
  // safe to hardcode (it's the publishable key, not the secret one).
  stripePublishableKey: '',
};
