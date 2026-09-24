/**
 * English translations, keyed by their exact French source string — see
 * TranslateService for why the key IS the French text rather than an
 * arbitrary identifier. Grouped by screen with a comment, purely for a human
 * reader; the lookup itself is a flat map.
 *
 * Every entry here should also exist, word-for-word, as a hardcoded French
 * string somewhere in a template piped through `translate` — a key with no
 * matching usage is dead. `{{name}}`-style placeholders are substituted by
 * TranslateService#translate's optional `params` argument.
 */
export const EN_DICTIONARY: Record<string, string> = {
  // --- App shell / navigation (shared/layout) ---
  Menu: 'Menu',
  Fermer: 'Close',
  'Mode sombre': 'Dark mode',
  'Mode clair': 'Light mode',
  'Se déconnecter': 'Log out',
  Voyages: 'Trips',
  'Mes abonnements': 'My subscriptions',
  'Mes statistiques': 'My statistics',
  'Tableau de bord organisateur': "Organiser's dashboard",
  'Mes voyages organisés': 'My organised trips',
  'Tableau de bord admin': 'Admin dashboard',
  Avis: 'Reviews',
  Signalements: 'Reports',
  Utilisateurs: 'Users',
  Paiements: 'Payments',
  Destinations: 'Destinations',
  Voyageur: 'Traveler',
  Organisateur: 'Organiser',
  Administration: 'Administration',
  Administrateur: 'Administrator',

  // --- Login (features/login) ---
  Email: 'Email',
  'Mot de passe': 'Password',
  'Se connecter': 'Log in',
  'Connexion…': 'Logging in…',
  "Pas encore de compte ?": 'No account yet?',
  'Créer un compte': 'Create an account',

  // --- Sign-up (features/register) ---
  'Je souhaite être': 'I want to be',
  'Parcourir les voyages, s’inscrire et suivre vos participations.':
    'Browse trips, sign up and track your participation.',
  'Créer et gérer vos propres voyages, en plus des fonctions voyageur.':
    'Create and manage your own trips, on top of the traveler features.',
  'Créer mon compte': 'Create my account',
  'Création…': 'Creating…',
  'Déjà un compte ?': 'Already have an account?',
  '{{n}} caractères minimum.': 'At least {{n}} characters.',

  // --- Traveler catalogue (features/travels) ---
  'Rechercher un voyage': 'Search a trip',
  'Destination, pays, activité, hébergement…': 'Destination, country, activity, accommodation…',
  Suggestions: 'Suggestions',
  Rechercher: 'Search',
  Effacer: 'Clear',
  'Chargement…': 'Loading…',
  '{{n}} résultat(s) pour « {{term}} »': '{{n}} result(s) for “{{term}}”',
  'Prix non renseigné': 'Price not specified',
  'Aucun voyage ne correspond à cette recherche.': 'No trip matches this search.',
  'Aucun voyage disponible.': 'No trip available.',
  'La recherche est momentanément indisponible : la liste complète des voyages est affichée.':
    'Search is temporarily unavailable: the full trip list is shown instead.',
  'La recherche a échoué.': 'The search failed.',
  'Impossible de charger les voyages.': 'Unable to load the trips.',

  // --- Travel detail (features/travels) ---
  'Retour aux voyages': 'Back to trips',
  Informations: 'Information',
  Dates: 'Dates',
  Durée: 'Duration',
  '{{n}} jour(s)': '{{n}} day(s)',
  Prix: 'Price',
  Capacité: 'Capacity',
  '{{n}} place(s)': '{{n}} seat(s)',
  Inscription: 'Sign-up',
  'Vous êtes inscrit à ce voyage.': "You're signed up for this trip.",
  "Le délai d'annulation ({{n}} jours avant le départ) est dépassé : une désinscription sera refusée.":
    'The cancellation window ({{n}} days before departure) has passed: unsubscribing will be refused.',
  "Vous pouvez annuler jusqu'au {{date}} ({{n}} jours avant le départ).":
    'You can cancel until {{date}} ({{n}} days before departure).',
  'Annulation…': 'Cancelling…',
  'Se désinscrire': 'Unsubscribe',
  'Compagnons de voyage': 'Travel buddies',
  'Me rendre visible aux autres voyageurs inscrits à ce voyage':
    'Make me visible to other travelers signed up for this trip',
  '{{n}} autre(s) voyageur(s) visible(s) sur ce voyage :': '{{n}} other traveler(s) visible on this trip:',
  'Aucun autre voyageur visible pour le moment.': 'No other traveler visible yet.',
  'Impossible de mettre à jour votre visibilité.': 'Unable to update your visibility.',
  "Ce voyage a déjà commencé : l'inscription n'est plus possible.":
    'This trip has already started: signing up is no longer possible.',
  "Vous pourrez annuler jusqu'à {{n}} jours avant le départ.":
    'You will be able to cancel up to {{n}} days before departure.',
  'Moyen de paiement': 'Payment method',
  'Paiement manuel': 'Manual payment',
  'Carte bancaire (Stripe)': 'Card payment (Stripe)',
  'Un administrateur confirmera votre règlement (virement, espèces…) : votre place est retenue 72 h en attendant.':
    'An administrator will confirm your payment (transfer, cash…): your seat is held for 72 h in the meantime.',
  "Vous saisirez votre carte bancaire à l'étape suivante (paiement sécurisé, géré par Stripe).":
    "You'll enter your card details on the next step (secure payment, handled by Stripe).",
  'Inscription…': 'Signing up…',
  "S'inscrire": 'Sign up',
  'Votre inscription a été annulée.': 'Your registration has been cancelled.',
  "Désinscription refusée : il reste moins de {{n}} jours avant le départ. L'annulation n'était possible que jusqu'au {{deadline}}.":
    "Unsubscribing refused: less than {{n}} days remain before departure. Cancelling was only possible until {{deadline}}.",
  'Aucune inscription active à annuler pour ce voyage.': 'No active registration to cancel for this trip.',
  'Impossible d’annuler votre inscription.': 'Unable to cancel your registration.',
  'Votre réservation a été annulée.': 'Your reservation has been cancelled.',
  'Impossible d’annuler cette réservation.': 'Unable to cancel this reservation.',
  'Réservation enregistrée : votre place est retenue, il reste à régler le paiement.':
    'Reservation recorded: your seat is held, payment is still due.',
  'Paiement reçu. Votre inscription est confirmée.': 'Payment received. Your registration is confirmed.',
  'Merci, votre avis a été enregistré.': 'Thank you, your review has been recorded.',
  'Ce voyage est introuvable.': 'This trip could not be found.',
  'Impossible de charger ce voyage.': 'Unable to load this trip.',
  'Inscription impossible : ce voyage a déjà commencé, il est complet, ou vous y êtes déjà inscrit (ou en attente de paiement).':
    'Unable to sign up: this trip has already started, is full, or you are already signed up (or awaiting payment).',
  'Le service de paiement est momentanément indisponible : rien n’a été réservé, vous pouvez réessayer.':
    'The payment service is temporarily unavailable: nothing was reserved, you can try again.',
  'Impossible de vous inscrire à ce voyage.': 'Unable to sign you up for this trip.',
  'Annuler votre inscription à ce voyage ?': 'Cancel your registration for this trip?',
  'Annuler cette réservation en attente de paiement ?': 'Cancel this reservation pending payment?',
  Activités: 'Activities',
  'Aucune activité renseignée.': 'No activity listed.',
  Hébergements: 'Accommodation',
  'Aucun hébergement renseigné.': 'No accommodation listed.',
  'Votre avis': 'Your review',
  'Ce voyage est terminé : donnez votre avis (un seul avis par voyage, non modifiable).':
    'This trip is over: leave your review (one review per trip, not editable).',
  "Voir la page de l'organisateur (statistiques, notes)": "View the organiser's page (statistics, ratings)",
  'Signalements reçus par cet organisateur :': 'Reports received by this organiser:',
  "Votre signalement a été transmis à l'équipe de modération.": 'Your report has been sent to the moderation team.',
  'Motif du signalement': 'Reason for the report',
  'Envoi…': 'Sending…',
  'Envoyer le signalement': 'Send the report',
  Annuler: 'Cancel',
  'Impossible d’envoyer ce signalement.': 'Unable to send this report.',
  "Signaler l'organisateur": 'Report the organiser',

  // --- Pending payment panel (features/subscriptions) ---
  'En attente de paiement': 'Payment pending',
  Expirée: 'Expired',
  'Mode de paiement inconnu': 'Unknown payment method',
  "Le délai de paiement est dépassé : la place n'est plus réservée. Vous pouvez vous réinscrire.":
    'The payment deadline has passed: the seat is no longer reserved. You can sign up again.',
  'Votre place est réservée encore': 'Your seat is still held for',
  "(jusqu'au {{date}}). Passé ce délai, la réservation expire.":
    '(until {{date}}). Past this deadline, the reservation expires.',
  'Paiement manuel : un administrateur doit confirmer la réception de votre règlement (virement, espèces…). Votre inscription deviendra active dès cette confirmation, vous n\'avez rien d\'autre à faire ici.':
    "Manual payment: an administrator must confirm receipt of your payment (transfer, cash…). Your registration will become active as soon as this is confirmed — there's nothing else for you to do here.",
  "Approuvez le paiement sur PayPal, puis revenez ici : le paiement est finalisé automatiquement au retour, ou avec le bouton « J'ai approuvé le paiement ».":
    'Approve the payment on PayPal, then come back here: the payment is finalised automatically on your return, or with the "I approved the payment" button.',
  "Payer avec PayPal": 'Pay with PayPal',
  "J'ai approuvé le paiement": 'I approved the payment',
  'Finalisation…': 'Finalising…',
  "Le lien PayPal d'origine n'est plus disponible dans ce navigateur (il n'est fourni qu'à l'inscription). Si vous avez déjà approuvé le paiement, utilisez « J'ai approuvé le paiement » ; sinon annulez la réservation et recommencez.":
    'The original PayPal link is no longer available in this browser (it is only provided at sign-up time). If you already approved the payment, use "I approved the payment"; otherwise cancel the reservation and start again.',
  "Paiement par carte (Stripe) : le paiement est créé chez Stripe, mais ce site n'est pas configuré pour collecter la carte (clé publique Stripe manquante). La confirmation arrive du webhook Stripe et active l'inscription si le paiement est réglé autrement. Sans règlement avant l'échéance, la réservation expire.":
    "Card payment (Stripe): the payment is created with Stripe, but this site isn't configured to collect the card (missing Stripe publishable key). Confirmation comes from the Stripe webhook and activates the registration if paid another way. Without payment before the deadline, the reservation expires.",
  "Le formulaire de carte n'est disponible qu'au moment de l'inscription et n'est pas conservé d'une visite à l'autre. Si vous avez déjà payé, actualisez l'état ci-dessous ; sinon annulez la réservation et recommencez pour obtenir un nouveau formulaire.":
    "The card form is only available right when you sign up and isn't kept between visits. If you already paid, refresh the status below; otherwise cancel the reservation and start again to get a new form.",
  'Réglez par carte bancaire (paiement sécurisé, géré par Stripe) :':
    'Pay by card (secure payment, handled by Stripe):',
  'Chargement du formulaire de paiement…': 'Loading the payment form…',
  'Payer par carte': 'Pay by card',
  'Paiement en cours…': 'Payment in progress…',
  'Référence :': 'Reference:',
  "Actualiser l'état du paiement": 'Refresh the payment status',
  "Nous n'avons pas pu déterminer le mode de paiement de cette réservation.":
    "We couldn't determine this reservation's payment method.",
  'Actualiser': 'Refresh',
  "Annuler la réservation": 'Cancel the reservation',

  // Messages set from pending-payment.component.ts (apostrophes there are typographic: ’).
  'Ce paiement a échoué : la réservation va être annulée. Vous pouvez recommencer.':
    'This payment failed: the reservation will be cancelled. You can start again.',
  'Le paiement n’est pas encore confirmé.': 'The payment has not been confirmed yet.',
  'Lien de paiement PayPal indisponible ou non fiable : annulez la réservation et recommencez.':
    'PayPal payment link unavailable or untrusted: cancel the reservation and start again.',
  'Référence PayPal introuvable : annulez la réservation et recommencez.':
    'PayPal reference not found: cancel the reservation and start again.',
  'PayPal n’a pas confirmé le paiement.': 'PayPal did not confirm the payment.',
  'Le paiement par carte n’est pas configuré sur ce site (clé Stripe manquante).':
    'Card payment is not configured on this site (missing Stripe key).',
  'Le paiement a été refusé par Stripe.': 'The payment was declined by Stripe.',
  'Paiement transmis à Stripe : confirmation en cours…': 'Payment sent to Stripe: confirmation in progress…',
  'Paiement reçu. Votre inscription sera confirmée dans quelques instants.':
    'Payment received. Your registration will be confirmed shortly.',
  'Ce paiement a déjà été traité : actualisez la page pour voir l’état de votre inscription.':
    'This payment has already been processed: refresh the page to see your registration status.',
  'Commande PayPal introuvable.': 'PayPal order not found.',
  'PayPal a refusé la capture : le paiement n’a pas abouti. Annulez la réservation et recommencez.':
    'PayPal refused the capture: the payment did not go through. Cancel the reservation and start again.',
  'Impossible de finaliser le paiement.': 'Unable to finalise the payment.',

  // --- My subscriptions (features/subscriptions) ---
  'Voyages à venir': 'Upcoming trips',
  'Voyages effectués': 'Trips taken',
  Annulations: 'Cancellations',
  'Paiement en attente': 'Payment pending',
  Voyage: 'Trip',
  Pays: 'Country',
  Départ: 'Departure',
  Statut: 'Status',
  'Inscrit le': 'Signed up on',
  'Annulé le': 'Cancelled on',
  'Mon avis': 'My review',
  'Donner / voir mon avis': 'Give / view my review',
  'Aucun voyage.': 'No trip.',
  'À venir': 'Upcoming',
  'Abonnements annulés': 'Cancelled subscriptions',
  'Réservations expirées (jamais payées)': 'Expired reservations (never paid)',
  Active: 'Active',
  Annulée: 'Cancelled',
  'Impossible de charger vos abonnements.': 'Unable to load your subscriptions.',
  'Annuler la réservation en attente pour {{name}} ?': 'Cancel the pending reservation for {{name}}?',
};
