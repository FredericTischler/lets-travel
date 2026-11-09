/** English translations for the manager screens — see en.dictionary.ts for the key convention. */
export const EN_MANAGER_DICTIONARY: Record<string, string> = {
  // --- Shared by several manager/stats screens ---
  Actions: 'Actions',
  Abonnés: 'Subscribers',
  'Note moyenne': 'Average rating',
  '{{n}} avis': '{{n}} review(s)',
  'Voyages organisés': 'Organised trips',
  Voyageurs: 'Travelers',
  'Signalements reçus': 'Reports received',
  'Mes voyages': 'My trips',
  Retour: 'Back',
  Note: 'Rating',

  // --- Subscribers of a travel (features/manager/travel-subscribers) ---
  'Liste des abonnés': 'Subscriber list',
  '{{n}} abonné(s) actif(s) sur {{capacity}} places.': '{{n}} active subscriber(s) out of {{capacity}} seats.',
  '{{n}} abonné(s) actif(s).': '{{n}} active subscriber(s).',
  Désinscrire: 'Unsubscribe',
  'Désinscription…': 'Unsubscribing…',
  'Aucun abonné pour ce voyage.': 'No subscriber for this trip.',
  "Vous n'êtes pas l'organisateur de ce voyage.": 'You are not the organiser of this trip.',
  'Impossible de charger les abonnés.': 'Unable to load the subscribers.',
  'Désinscrire le voyageur {{id}} de ce voyage ?': 'Unsubscribe traveler {{id}} from this trip?',
  'Impossible de désinscrire ce voyageur.': 'Unable to unsubscribe this traveler.',

  // --- Feedback of a travel (features/manager/travel-feedback) ---
  'Retour à mes voyages': 'Back to my trips',
  Synthèse: 'Summary',
  '{{n}} avis · note moyenne :': '{{n}} review(s) · average rating:',
  'Tous les avis': 'All reviews',
  "Ce voyage n'a reçu aucun avis pour l'instant.": 'This trip has not received any review yet.',
  'Vous ne pouvez consulter que les avis de vos propres voyages.':
    'You can only view the reviews of your own trips.',
  'Impossible de charger les avis de ce voyage.': 'Unable to load the reviews of this trip.',

  // --- My travels (features/manager/my-travels) ---
  Nom: 'Name',
  Modifier: 'Edit',
  Supprimer: 'Delete',
  'Suppression…': 'Deleting…',
  "Vous n'organisez aucun voyage pour le moment.": "You aren't organising any trip at the moment.",
  'Supprimer le voyage {{name}} ?': 'Delete the trip {{name}}?',
  'Impossible de charger vos voyages.': 'Unable to load your trips.',
  'Impossible de modifier ce voyage.': 'Unable to edit this trip.',
  'Impossible de créer ce voyage.': 'Unable to create this trip.',
  'Impossible de supprimer ce voyage.': 'Unable to delete this trip.',

  // --- Manager dashboard (features/manager/manager-dashboard) ---
  'Période des revenus': 'Income period',
  '{{n}} derniers mois': 'Last {{n}} months',
  'Données de revenus indisponibles pour le moment (service de paiement injoignable) : les autres statistiques restent exactes.':
    'Income data is temporarily unavailable (payment service unreachable): the other statistics remain accurate.',
  'Revenus (total)': 'Income (total)',
  Indisponible: 'Unavailable',
  '{{n}} derniers mois : {{amount}}': 'Last {{n}} months: {{amount}}',
  '{{past}} terminés · {{ongoing}} en cours · {{upcoming}} à venir':
    '{{past}} completed · {{ongoing}} ongoing · {{upcoming}} upcoming',
  'Inscrits actifs distincts': 'Distinct active sign-ups',
  'Revenus par mois ({{currency}})': 'Income per month ({{currency}})',
  'Revenus par mois sur les {{n}} derniers mois': 'Income per month over the last {{n}} months',
  Revenus: 'Income',
  'Aucun revenu encaissé sur la période.': 'No income collected over the period.',
  'Graphique indisponible : données de revenus indisponibles.': 'Chart unavailable: income data is unavailable.',
  'Derniers avis': 'Latest reviews',
  "Aucun avis reçu pour l'instant.": 'No review received yet.',
  'Gérer mes voyages': 'Manage my trips',
  'Impossible de charger le tableau de bord.': 'Unable to load the dashboard.',
  'Autres devises : {{amounts}}': 'Other currencies: {{amounts}}',

  // --- Public manager page (features/managers) ---
  "Page de l'organisateur": "Organiser's page",
  'Identifiant : {{id}}': 'ID: {{id}}',
  "Cet organisateur n'a aucun voyage ni aucun avis pour le moment.":
    'This organiser has no trip or review at the moment.',
  'Voyages terminés': 'Completed trips',
  'Voyageurs inscrits': 'Signed-up travelers',
  'Notes des voyages passés': 'Ratings of past trips',
  "Aucun voyage terminé pour l'instant.": 'No completed trip yet.',
  'Signaler cet organisateur': 'Report this organiser',
  'Vous ne pouvez pas vous signaler vous-même.': 'You cannot report yourself.',
  'Impossible de charger cette page organisateur.': 'Unable to load this organiser page.',

  // --- Traveler statistics (features/stats) ---
  'Avis donnés': 'Reviews given',
  'Inclut les annulations par un organisateur ou un paiement échoué.':
    'Includes cancellations by an organiser or due to a failed payment.',
  'Moyens de paiement préférés': 'Preferred payment methods',
  'Données de paiement indisponibles pour le moment (service de paiement injoignable).':
    'Payment data is temporarily unavailable (payment service unreachable).',
  "Aucun paiement effectué pour l'instant.": 'No payment made yet.',
  'Moyen le plus utilisé :': 'Most used method:',
  '{{n}} paiement(s) terminé(s)': '{{n}} completed payment(s)',
  Moyen: 'Method',
  Montants: 'Amounts',
  'Historique des voyages effectués': 'History of trips taken',
  Donné: 'Given',
  'Donner mon avis': 'Give my review',
  "Aucun voyage effectué pour l'instant.": 'No trip taken yet.',
  'Vos avis': 'Your reviews',
  "Vous n'avez donné aucun avis pour l'instant.": "You haven't given any review yet.",
  'Impossible de charger vos statistiques.': 'Unable to load your statistics.',

  // --- Travel rows table (features/stats/travel-rows-table) ---
  Revenu: 'Income',
  indisponible: 'unavailable',
  'Voir les avis': 'View reviews',
  'En cours': 'Ongoing',
  Terminé: 'Completed',

  // --- Recommendations (features/recommendations) ---
  'Suggestions pour vous': 'Suggestions for you',
  'Chargement de vos suggestions…': 'Loading your suggestions…',
  'Classées selon votre historique (voyages effectués et notes données). Le score est la somme des points indiqués dans « Pourquoi ? ».':
    'Ranked according to your history (trips taken and ratings given). The score is the sum of the points listed under “Why?”.',
  '· score {{score}}': '· score {{score}}',
  'Pourquoi ?': 'Why?',
  'Aucune suggestion pour le moment : tous les voyages à venir sont déjà réservés ou complets.':
    'No suggestion at the moment: all upcoming trips are already booked or full.',
  'Impossible de charger vos suggestions.': 'Unable to load your suggestions.',
};
