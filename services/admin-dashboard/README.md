# admin-dashboard

Front **Angular 22** de Let's Travel : application standalone, routes lazy-loaded,
signals, Tailwind 4, tests Vitest + Playwright. Depuis la Phase 8 c'est une
application **selon le rôle** (Admin / Travel Manager / Voyageur) et plus
seulement un CRUD d'administration.

Ce README décrit **ce qui existe réellement dans le code**. Ce qui est absent est
regroupé dans [Non implémenté](#non-implémenté).

## Démarrage

```bash
npm install
npm start      # ng serve -> http://localhost:4200
npm run build  # ng build
npm test       # ng test (Vitest)
```

Le front appelle les APIs **via la gateway Traefik**, en HTTPS, dans les deux
environnements (`src/environments/environment.ts` et `environment.development.ts` ont
les mêmes valeurs) :

| Clé | Valeur |
|---|---|
| `identityApiUrl` | `https://identity.localhost` |
| `paymentApiUrl` | `https://payment.localhost` |
| `travelApiUrl` | `https://travel.localhost` |

La stack backend doit donc tourner (profil `full`) et le certificat auto-signé de
Traefik être accepté par le navigateur. La recherche Elasticsearch demande en plus
le profil Compose `search` ; sans lui l'écran Voyages bascule sur la liste simple
(voir [Recherche](#recherche-voyageur)).

## Rôles, navigation et routes

Le rôle vient de la claim `role` du JWT (`AuthService.role`). La hiérarchie du sujet
est appliquée côté front comme côté backend (`core/auth/roles.ts`) :
**ADMIN ⊃ TRAVEL_MANAGER ⊃ TRAVELER** — un admin passe partout, un organisateur passe
aussi sur les écrans voyageur.

| Route | Écran | Rôle minimal | Backend appelé |
|---|---|---|---|
| `/login` | Connexion (lien vers l'inscription) | public | `POST /login` |
| `/register` | Inscription : email, mot de passe, **Voyageur ou Organisateur** (jamais Admin) ; connecte puis redirige | public | `POST /users` (avec `role`), `POST /login` |
| `/travels` | Catalogue + **suggestions Neo4j avec leurs raisons** + recherche Elasticsearch + autocomplétion | TRAVELER | `GET /destinations`, `/destinations/search?q=`, `/destinations/autocomplete?prefix=`, `GET /travelers/me/recommendations?limit=6` |
| `/travels/:id` | Détail d'un voyage : s'inscrire (**choix du moyen de paiement** si payant), panneau « en attente de paiement », se désinscrire, **avis** une fois le voyage terminé, lien vers la page de l'organisateur, signaler | TRAVELER | `GET /destinations/{id}`, `POST`/`DELETE /destinations/{id}/subscriptions`, `GET /travelers/me/subscriptions`, `GET /payments/{id}`, `POST /payments/paypal/{orderId}/capture`, `POST /destinations/{id}/feedback`, `GET /travelers/me/feedback`, `POST /reports`, `GET /reports/count/{userId}` |
| `/paypal/return` | Retour de PayPal (`?token=<orderId>`) : capture la commande | TRAVELER | `POST /payments/paypal/{orderId}/capture` |
| `/my-subscriptions` | Mes abonnements : **paiement en attente**, à venir, effectués (lien « avis »), annulés, **expirés** + compteurs | TRAVELER | `GET /travelers/me/subscriptions`, `GET /payments/{id}`, `DELETE /destinations/{id}/subscriptions` |
| `/my-stats` | **Mes statistiques** : voyages effectués / à venir, annulations, avis donnés, signalements reçus, moyens de paiement préférés, historique, « vos avis » | TRAVELER | `GET /travelers/me/stats`, `GET /travelers/me/feedback`, `GET /reports/count/{self}` |
| `/managers/:id` | **Page publique d'un organisateur** : statistiques, notes des voyages passés, nombre de signalements, bouton signaler | TRAVELER | `GET /managers/{id}/stats`, `GET /reports/count/{id}`, `POST /reports` |
| `/manager/dashboard` | **Tableau de bord organisateur** : revenus, voyages, voyageurs, note moyenne, revenus par mois (graphique + tableau), tableau par voyage, derniers avis (`?managerId=` pour un admin) | TRAVEL_MANAGER | `GET /managers/me/dashboard?months=` |
| `/manager/travels` | Mes voyages organisés : créer / modifier / supprimer (dates, prix, capacité, activités, hébergements), liens Abonnés et **Avis** | TRAVEL_MANAGER | `GET`, `POST`, `PUT`, `DELETE /destinations` |
| `/manager/travels/:id/subscribers` | Abonnés d'un voyage + désinscription forcée | TRAVEL_MANAGER | `GET /destinations/{id}/subscriptions`, `DELETE /destinations/{id}/subscriptions/{travelerId}` |
| `/manager/travels/:id/feedback` | **Avis reçus par un voyage** (contrôle qualité) : synthèse + liste | TRAVEL_MANAGER | `GET /destinations/{id}/feedback`, `GET /destinations/{id}` |
| `/admin/dashboard` | **Tableau de bord admin** : voyages organisés, revenus des derniers mois (graphique), top organisateurs (score / note / revenus), top voyages, **classement des organisateurs par score avec leur nombre de signalements**, historique détaillé, derniers avis | ADMIN | `GET /admin/dashboard?months=`, `GET /managers/ranking`, `GET /reports/count/{id}`, `GET /users` |
| `/admin/feedback` | **Tous les avis** (filtre par note) | ADMIN | `GET /feedback` |
| `/admin/reports` | File de modération des signalements (filtre par statut, `OPEN` → `REVIEWED`/`DISMISSED`/`ACTIONED`) | ADMIN | `GET /reports`, `PATCH /reports/{id}/status`, `GET /users` (emails, best-effort) |
| `/users` | Utilisateurs (liste avec rôle, **création avec sélecteur de rôle ADMIN / TRAVEL_MANAGER / TRAVELER**, modification de l'email, suppression) | ADMIN | `/users` |
| `/payments` | Paiements manuels (liste, création, transition de statut — c'est ici qu'un admin **confirme un paiement MANUAL**, suppression) | ADMIN | `/payments` |
| `/destinations` | Toutes les destinations (tous organisateurs) : créer (l'admin saisit l'id de l'organisateur), modifier, supprimer, trajets `TRANSPORT`, lien vers les abonnés | ADMIN | `/destinations`, `/destinations/{id}/transports` |

Page d'accueil par rôle (`/`, URL inconnue, après login) : ADMIN → `/users`,
TRAVEL_MANAGER → `/manager/travels`, TRAVELER → `/travels`.

**Fallbacks de rôle** (`core/guards/role.guard.ts`) : pas de token → `/login` ; rôle connu
mais interdit sur la route → page d'accueil de son rôle ; token **sans rôle ou avec un
rôle inconnu** (ex. token d'avant la Phase 1) → le token est purgé et redirection vers
`/login`, plutôt qu'une boucle de redirection.

Ces guards ne sont **qu'un confort d'interface** : chaque appel est revérifié par le
backend (403), un front modifié n'obtient aucun droit supplémentaire.

### Shell (`shared/layout/`)

`AppShellComponent` est piloté par les données : `nav-items.ts` liste
`{label, route, roles, group?}` et le shell n'affiche que les entrées permises au rôle
courant (même fonction `hasAccess` que les guards). Les entrées qui partagent un
`group` (« Voyageur », « Organisateur », « Administration ») s'affichent sous une
section repliable — utile pour un ADMIN, qui hérite de toutes les entrées par
hiérarchie de rôle et verrait sinon 11 liens à plat (`navSectionsFor()`, ouvert par
défaut, état par groupe en signal, générique sur le nombre de groupes). Le shell
affiche aussi l'email (claim `email`), un badge de rôle, la bascule de thème et le
bouton **Se déconnecter** (`AuthService.logout()` puis `/login`). Ajouter un écran =
une route gardée + une entrée dans `nav-items.ts` (avec ou sans `group`).

**Responsive** : sous le breakpoint `lg` (1024 px) la navigation en sidebar et les
contrôles se replient derrière un bouton « Menu » (`aria-expanded`), refermé au clic
sur un lien. Il n'y a qu'**un seul** `<nav>` dans le DOM (le CSS décide sidebar ou
liste déroulante).
Les listes de données sont des tableaux à défilement horizontal (`table-shell`) ou des
grilles de cartes `1 / 2 / 3` colonnes ; les formulaires passent en une colonne sur mobile.
Vérifié visuellement à 375 px (menu mobile) sur le dev server ; pas de test automatisé
multi-navigateurs au-delà du spec Playwright « on a phone ».

## Détails par écran

### Recherche (voyageur)

- Frappe → `GET /destinations/autocomplete` après **300 ms** de debounce (`switchMap` :
  une réponse périmée est écartée), à partir de 2 caractères ; navigation clavier
  (↑ ↓ Entrée Échap) et ARIA `combobox`/`listbox`.
- Envoi du formulaire → `GET /destinations/search?q=` ; requête vide → liste simple.
- **Elasticsearch indisponible (503)** sur l'un ou l'autre appel : pas d'erreur bloquante,
  l'écran affiche la liste complète des voyages avec un bandeau « recherche momentanément
  indisponible ». C'est la liste **non filtrée** (pas de filtre client de remplacement).
  Toute autre erreur reste une erreur affichée.

### Abonnement (voyageur)

- L'état « déjà inscrit » vient de l'historique personnel (`GET /travelers/me/subscriptions`,
  ligne `ACTIVE` pour ce voyage).
- Le délai d'annulation de **3 jours** (`features/subscriptions/cancellation-rules.ts`,
  miroir de la règle backend `startDate < aujourd'hui + 3 j`) est annoncé avant le clic
  (date limite, ou avertissement si déjà dépassé) ; le bouton reste actif : **le backend
  fait foi**, son 409 est converti en message explicite (« Désinscription refusée : il
  reste moins de 3 jours… »). Un voyage déjà commencé désactive l'inscription.
- « Mes abonnements » sépare à venir / effectués (`ACTIVE` dont la date de départ est
  passée) / annulés, avec les trois compteurs (participations passées, annulations : les
  statistiques personnelles du sujet, partie abonnements).

### Paiement d'un abonnement (voyageur)

- Un voyage **gratuit** (prix 0/absent) est `ACTIVE` tout de suite, sans corps de requête. Un voyage
  **payant** demande le moyen de paiement (`provider` : PayPal par défaut, Stripe, manuel) ; la réponse
  est `PENDING_PAYMENT` avec un objet `payment` (id, `approveUrl` PayPal, `clientSecret` Stripe) et
  `expiresAt` (60 min Stripe/PayPal, 72 h manuel — règle du backend).
- Le panneau **« En attente de paiement »** (`features/subscriptions/pending-payment.component`) montre
  le moyen, le montant, le **compte à rebours** jusqu'à `expiresAt` (la réservation retient une place),
  et selon le moyen :
  - **MANUAL** : « un administrateur doit confirmer la réception de votre règlement » — il le fait sur
    l'écran `/payments` (`PATCH /payments/{id}/status`), payment-service prévient alors travel-service et
    l'abonnement devient `ACTIVE`.
  - **PAYPAL** : bouton « Payer avec PayPal » (redirection vers `approveUrl`, **refusée si l'URL n'est pas
    en `https` sur `paypal.com`**), puis capture (`POST /payments/paypal/{orderId}/capture`) : soit
    automatiquement au retour sur `/paypal/return?token=<orderId>`, soit avec le bouton « J'ai approuvé le
    paiement ». L'id de commande est le paramètre `token` de l'URL d'approbation, ou `externalReference`
    relu par `GET /payments/{id}`. L'URL d'approbation n'est renvoyée **qu'une fois** par l'API : elle est
    mémorisée dans le `localStorage` du navigateur (`PendingPaymentStore`, jamais de secret) pour reprendre
    un paiement depuis « Mes abonnements ».
  - **STRIPE** : voir [Non implémenté](#non-implémenté) — le panneau affiche la référence et l'état, dit
    honnêtement que le formulaire de carte n'est pas intégré, et propose « Actualiser l'état ». Le
    `clientSecret` n'est ni affiché ni stocké.
- « Annuler la réservation » (`DELETE …/subscriptions`) est toujours permis pour une réservation non
  payée, même à moins de 3 jours du départ (règle du backend). `EXPIRED` (dérivé côté backend) et un
  `PENDING_PAYMENT` échu sont rangés dans « Réservations expirées » et on peut se réinscrire.
- `502` (payment-service injoignable) : « rien n'a été réservé, vous pouvez réessayer » — le backend a
  compensé.

### Avis (feedback)

- Le formulaire (note 1–5 + commentaire facultatif ≤ 1000 caractères, texte brut) n'est proposé, sur le
  détail d'un voyage, qu'à un **participant `ACTIVE` d'un voyage terminé** (`endDate < aujourd'hui`) qui n'a
  pas encore donné son avis (`GET /travelers/me/feedback`). Un avis est **unique et non modifiable** : le
  formulaire le dit, et les 403/409 du backend sont traduits (« pas participé », « pas terminé / déjà donné »).
- Le voyageur retrouve « Vos avis » sur `/my-stats`, le lien « Donner / voir mon avis » sur les voyages
  effectués, et l'organisateur / l'admin voient les avis (`/manager/travels/:id/feedback`, dashboards,
  `/admin/feedback`) avec l'**id** de l'auteur (jamais une identité).
- Tout commentaire est rendu **par interpolation** (`whitespace-pre-line`), jamais `[innerHTML]` ; des specs
  vérifient qu'un `<img onerror>` / `<script>` s'affiche littéralement.

### Suggestions (recommandations)

Bloc « Suggestions pour vous » en tête de `/travels` (limite 6). Chaque carte montre le **score** et un
« Pourquoi ? » qui déplie les **raisons** renvoyées par le backend, chacune terminée par ses points signés
(`… which you rated 5 (+9)`) : la somme des raisons **est** le score (vérifié par un test), ce qui rend la
précision contrôlable à l'audit (deux comptes, deux historiques, deux listes). Les raisons sont en **anglais**
(écrites par le backend) et affichées telles quelles. Un échec ne casse que ce bloc.

### Statistiques personnelles (voyageur)

`/my-stats` : compteurs (effectués, à venir, annulations, avis donnés, **signalements reçus** via
`GET /reports/count/{self}` — absents du payload travel-service), moyens de paiement préférés (tableau par
moyen, montants **par devise, jamais sommés**), historique des voyages effectués. Si
`partial: true` (payment-service injoignable) le bloc paiement affiche « Données de paiement indisponibles »
au lieu de zéros.

### Page publique d'un organisateur

`/managers/:id`, liée depuis le détail d'un voyage : voyages organisés / terminés, voyageurs inscrits, note
moyenne (un `—` sans avis, pas 0), signalements reçus, notes des voyages passés, bouton signaler (le flux de
signalement existant, ≤ 2000 caractères ; pas pour soi-même). Agrégats uniquement : le backend ne donne à un
voyageur ni avis individuel ni identité (juste l'id). Un id inconnu répond des zéros (le backend ne peut pas
dire 404) : la page l'indique.

### Tableaux de bord (organisateur / admin)

- **Organisateur** : 4 tuiles (revenus total + fenêtre, voyages organisés avec terminés/en cours/à venir,
  voyageurs, note moyenne), revenus par mois (fenêtre 3 / 6 / 12 mois), tableau par voyage (abonnés/capacité,
  note, revenu, lien vers ses avis), derniers avis.
- **Admin** : voyages organisés, organisateurs, voyageurs actifs, revenus, satisfaction ; graphique des revenus ;
  top organisateurs par score / note / revenus ; top voyages par revenus / note ; **classement complet** des
  organisateurs par score de performance (`GET /managers/ranking`) avec leur **nombre de signalements**
  (`GET /reports/count/{id}` par organisateur, `—` si l'appel échoue) et leur email (`GET /users`, sinon l'id) ;
  historique détaillé des voyages terminés ; derniers avis + lien vers tous les avis.
- **`partial: true`** (payment-service injoignable) : bandeau « Données de revenus indisponibles », tuiles
  « Indisponible », pas de graphique, `indisponible` dans les tableaux — jamais de zéros ; le reste du tableau
  de bord est servi. Sur l'admin, le bandeau précise que les scores sont alors calculés sans les revenus.
- Les montants sont des **cartes par devise** : la barre du graphique est la part en devise de référence
  (`amount`) ; toute autre devise du mois est écrite dans l'infobulle / le tableau, jamais additionnée.

### Composants de visualisation (`shared/ui/`)

Pas de bibliothèque de graphiques (le sujet demande de justifier chaque paquet) : trois petits composants
HTML/CSS. `app-bar-chart` (colonnes ≤ 24 px, sommet arrondi 4 px, grille en filet, **une seule couleur** =
slot 1 de la palette dataviz, jeux clair/sombre dans `styles.css` via `--viz-series-1`, infobulle au survol
**et au focus clavier**, valeur du pic étiquetée, **tableau équivalent** dans un `<details>`, axe à valeurs
rondes) ; `app-stat-tile` (tuile KPI) ; `app-rating` (étoiles décoratives + texte « 4 sur 5 »).
Texte jamais dans la couleur de la série. Vérifié dans un vrai navigateur (API simulée), clair et sombre, à
375 px (pas de défilement horizontal). Le script de validation de palette du skill dataviz n'était plus
disponible au moment de la vérification : la couleur utilisée est le slot 1 de la palette de référence, non
re-validée ici.

### Mes voyages organisés (organisateur)

- Le backend n'a pas de requête « par organisateur » : la liste est `GET /destinations`
  **filtrée côté client** sur `managerId === sub` du JWT. C'est un confort, pas une
  sécurité : le backend refuse (403) toute écriture sur le voyage d'un autre et toute
  création avec un `managerId` qui n'est pas le sien. Le champ organisateur n'est pas
  affiché : il est imposé (`fixedManagerId`).
- Le formulaire (`features/destinations/destination-form.component`) est **partagé** avec
  l'écran admin : validation prix ≥ 0, capacité entière ≥ 1, date de fin ≥ date de début,
  activités et hébergements dynamiques (`FormArray`). Le `PUT` n'envoie jamais `managerId`
  (l'appartenance n'est jamais réassignée).
- Abonnés : l'API n'expose que l'**id** du voyageur (pas d'email ni de profil) — c'est
  ce qui est affiché, avec le statut, les dates et le remplissage (actifs / capacité).

### Signalements

- Voyageur/organisateur : bouton « Signaler l'organisateur » sur le détail d'un voyage
  (motif ≤ 2000 caractères) et compteur de signalements reçus par l'organisateur.
  Le bouton n'est pas proposé sur ses propres voyages (le backend refuse l'auto-signalement).
- Admin : file triée `OPEN` d'abord, puis du plus récent ; les ids sont résolus en emails
  via `GET /users` quand il répond. Un signalement décidé est **immuable** côté backend
  (409) : aucune action n'est proposée dessus, et un 409 recharge la file.

## Structure

```
src/app/
  core/auth/          AuthService (JWT, claims sub/role/email, register), roles.ts (rôles, hiérarchie, accueils)
  core/guards/        authGuard, roleGuard(...roles), homeGuard
  core/interceptors/  authInterceptor
  core/theme/         ThemeService
  features/           login, register, travels (traveler), subscriptions (+ paiement), feedback,
                      recommendations, stats (service + stats voyageur + tableaux), managers (page publique),
                      manager (voyages + dashboard + avis), admin (dashboard), reports,
                      users, payments, destinations (admin + formulaire partagé)
  shared/layout/      AppShellComponent + nav-items.ts
  shared/ui/          alert, badge, button, card, input, bar-chart, stat-tile, rating
  shared/format.ts    formats fr (montants par devise, note, mois)
  shared/http-error.ts  extraction du message d'erreur backend
e2e/                  specs Playwright + support/ (création de comptes/voyages par API)
```

Pattern d'un écran : `features/<nom>/*.component.ts|html` + `*.service.ts` (HTTP) +
`.spec.ts`. Composants réutilisables : `app-alert`, `app-badge` (statuts/rôles),
`app-button`, `app-card`, `app-input`, `app-destination-form`, `app-bar-chart`, `app-stat-tile`,
`app-rating`, `app-feedback-list`, `app-feedback-form`, `app-pending-payment`.

## Authentification (état réel)

- `AuthService` conserve le JWT dans `localStorage` (clé `admin-dashboard.jwt`) et
  l'expose via un signal ; il en décode les claims `sub`, `role` et `email`.
- `authInterceptor` ajoute `Authorization: Bearer <token>` à toute requête dont l'URL
  commence par l'une des 3 URLs d'API, et **sur 401** : purge le token et redirige
  vers `/login`.
- **Pas de refresh token, pas de renouvellement silencieux** : le JWT backend dure
  15 min, à l'expiration l'utilisateur retombe sur `/login`.
- `AuthService.decodeJwtPayload()` ne fait **aucune vérification de signature** — il ne
  sert qu'à lire des claims d'un token que le backend re-vérifie à chaque requête (le rôle
  lu ici ne décide que de ce qui est *affiché*).
- **Inscription** : `POST /users` est public côté backend. Le formulaire n'offre que
  TRAVELER et TRAVEL_MANAGER et refuse d'envoyer un autre rôle. **C'est de l'UX** : la
  protection est côté backend, qui répond 403 à `role: ADMIN` sans jeton admin
  (`docs/security-audit.md`). L'écran admin « Utilisateurs » a un **sélecteur de rôle**
  (ADMIN / TRAVEL_MANAGER / TRAVELER, `TRAVELER` par défaut) et envoie toujours le rôle
  explicitement ; le jeton admin est ajouté par l'intercepteur, ce qui autorise `ADMIN`.

## Thème clair / sombre

- `ThemeService` : signal `theme` (`'light' | 'dark'`) + `effect` qui bascule la classe
  `dark` sur `<html>` et persiste le choix dans `localStorage`
  (clé `admin-dashboard.theme`).
- Un **script inline dans `index.html`** applique le thème persisté avant le bootstrap
  Angular, pour éviter un flash du mauvais thème.

## Sécurité front (XSS)

Aucun `[innerHTML]`, aucun `bypassSecurityTrust*` dans le code : tout texte libre
(motif d'un signalement, **commentaires d'avis**, noms de voyages, activités…) passe par l'interpolation Angular,
qui échappe. Des specs le vérifient (motif de signalement contenant du HTML, nom de voyage
contenant une balise).

## Tests

**Vitest** — `npm test` : 49 fichiers, 353 tests, tous verts. Couvrent : rôles/hiérarchie,
guards (`roleGuard`, `homeGuard`, fallbacks de rôle inconnu), `AuthService` (rôle, email,
inscription), shell et navigation par rôle (dont menu mobile et logout), règles du délai
de 3 jours, services HTTP (destinations, abonnements, signalements, paiements, avis,
statistiques, suggestions), et les composants avec logique (formulaire de destination,
catalogue avec debounce/503, détail voyage avec ses cas 409, choix du moyen de paiement,
panneau de paiement en attente, retour PayPal, avis, mes abonnements, mes voyages, abonnés,
file de signalements, inscription, connexion, destinations admin, **sélecteur de rôle de
l'écran Utilisateurs**, suggestions, statistiques voyageur, page organisateur, tableaux de
bord organisateur/admin, liste des avis). Sont testés en particulier : le compte à rebours,
la lecture de l'id de commande PayPal, le refus de rediriger vers une URL non-`paypal.com`,
le mode dégradé `partial: true` (« indisponible », jamais 0), la somme des raisons = score,
et l'échappement de tout texte libre (commentaire d'avis, nom de voyage, raison).
`ng build` est vert.

**Vérification dans un vrai navigateur** : les nouvelles pages (suggestions, détail avec
paiement en attente, mes abonnements, mes statistiques, page organisateur, dashboards
organisateur et admin, nominal et `partial`) ont été ouvertes contre une **API simulée**
(un petit serveur statique qui injecte un `fetch` factice dans le build — rien dans le code
livré), en clair et en sombre, à 375 px. Cela a fait corriger trois défauts d'affichage
(tuile de note qui débordait, « Indisponible » coupé en mobile, libellés de mois tronqués).
Aucun test contre le vrai backend, ni contre PayPal/Stripe.

**Playwright** (`npm run e2e`, contre le vrai backend, sans mock) — **les specs ci-dessous
ont été écrites mais NON exécutées** : la suite exige la stack Docker complète, dont les
images sont périmées et qui n'a volontairement pas été démarrée. Ils sont listés par
Playwright (`playwright test --list` : 30 tests, 13 fichiers), rien de plus n'est garanti.

| Spec | Statut |
|---|---|
| `payment-flow.spec.ts` (**nouveau** : paiement manuel → réservation en attente + explication → confirmation admin → abonnement actif ; annulation d'une réservation en attente) | écrit, non exécuté |
| `dashboards.spec.ts` (**nouveau** : dashboard organisateur, dashboard admin + classement avec signalements, suggestions + statistiques + page organisateur + signalement côté voyageur, pas de formulaire d'avis avant la fin du voyage) | écrit, non exécuté |
| `admin-user-roles.spec.ts` (**nouveau** : création d'un compte TRAVEL_MANAGER par l'admin, sélecteur de rôle) | écrit, non exécuté |
| `role-navigation.spec.ts` | **étendu** (nouvelles routes et entrées de navigation) — non exécuté |
| `subscription-flow.spec.ts` | **modifié** : voyages **gratuits** (`price: 0`), sinon l'inscription ouvrirait un vrai paiement PayPal — non exécuté |
| `reports.spec.ts`, `travel-search.spec.ts` | **modifiés** : lien du catalogue ciblé par `catalogueLink()` (`support/travel-api.ts`), car le bloc de suggestions peut afficher le même voyage — non exécutés |
| `register.spec.ts`, `manager-travels.spec.ts` | écrits (phase précédente), non exécutés |
| `destinations.spec.ts` | modifié en phase précédente (prix, capacité, organisateur requis) — non ré-exécuté ; dernière exécution : 2026-09-17 |
| `login`, `auth-guard`, `payments` | inchangés ; dernière exécution confirmée 2026-09-17, avant la Phase 8 |

**Non couvert de bout en bout, faute de moyen** : PayPal et Stripe réels (aucun compte ;
la redirection + capture PayPal et le cas Stripe sont testés en Vitest avec des réponses
simulées), et le **dépôt d'un avis** (un voyage terminé ne peut pas être préparé par l'API :
l'abonnement à un voyage passé est refusé) — le formulaire et ses erreurs sont testés en Vitest.

Les specs créent leurs comptes par `POST /users`. `TRAVELER` et `TRAVEL_MANAGER` passent par
l'inscription publique ; **`ADMIN` ne le peut plus** (403 sans jeton admin, et un rôle omis
donne désormais `TRAVELER`, plus jamais `ADMIN`). `createTestUser(request)` (défaut `ADMIN`)
se connecte donc avec **l'admin de bootstrap** (créé au démarrage d'identity-service depuis
Vault `secret/identity/bootstrap-admin`) puis crée le compte avec son jeton. Ses identifiants
se passent par l'environnement : `E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... npm run e2e`
(placeholders de dev dans `ansible/roles/vault/defaults/main.yml`) ; le helper échoue avec un
message explicite s'ils manquent.

## Non implémenté

- **Stripe.js configuré avec une vraie clé publiable** : le front intègre désormais le
  formulaire de carte (`@stripe/stripe-js`, Payment Element monté avec le `clientSecret` reçu
  à l'inscription, confirmation via `stripe.confirmPayment`, puis sondage de
  `GET /payments/{id}` jusqu'à ce que le webhook Stripe passe le paiement à `COMPLETED`) —
  voir `PendingPaymentComponent`/`StripeCheckoutService`. Il manque uniquement une clé
  `pk_test_...` réelle (`environment.stripePublishableKey`, vide par défaut) correspondant au
  `STRIPE_SECRET_KEY` du backend : sans elle, l'écran affiche honnêtement « paiement par carte
  non configuré » plutôt que d'essayer de charger Stripe.js. Non exercé de bout en bout dans
  cet environnement (le payment-service tourne avec des identifiants Stripe/PayPal factices
  côté Vault, donc `POST /payments/stripe` répond 502 même avec le formulaire branché) ; le
  montage du formulaire, la confirmation et le refus sont couverts en Vitest avec un
  Stripe.js simulé. PayPal et le paiement manuel restent les moyens payables de bout en bout
  dans cet environnement. Le flux PayPal (redirection +
  capture) est implémenté mais **jamais exercé contre PayPal** ; il suppose que `/paypal/return`
  est configuré comme URL de retour côté PayPal (le backend crée la commande sans URL de
  retour), sinon le bouton « J'ai approuvé le paiement » fait la même capture.
- **Remboursement / paiement échoué distingué d'une annulation** : un `FAILED` devient
  `CANCELLED` côté backend et gonfle le compteur d'annulations (limite documentée en ADR §4).
- **Avis modifiables ou modérables** : un avis est unique et immuable (décision backend).
- **Profil voyageur** vu par un organisateur : l'API n'expose que l'id du voyageur.
- ~~Pagination des listes d'avis / du classement~~ — **corrigé** : `GET /feedback`
  et `GET /managers/ranking` sont paginés côté backend (`page`/`size`, réponse
  `PageResponse<T>` — premier endpoint paginé du projet), consommés via un
  composant `app-paginator` partagé. Le filtre par note et la « note moyenne »
  de l'écran avis ne portent que sur la page affichée (pas de filtre par note
  côté backend, et une vraie moyenne plateforme demanderait un endpoint
  d'agrégat dédié) — assumé et affiché explicitement.
- **Page d'accueil par rôle** inchangée (`/users` pour l'admin, `/manager/travels` pour
  l'organisateur) : les dashboards sont dans la navigation, pas la page d'atterrissage.
- **PWA** et **i18n** (bonus du sujet) : non faits. Les libellés sont en français,
  codés en dur dans les templates, sans infrastructure de traduction ; les **raisons des
  suggestions** viennent du backend en **anglais**.
- **Trajets `TRANSPORT`** : gérés uniquement depuis l'écran admin `/destinations` (pas
  depuis l'écran organisateur). Création, édition inline et suppression désormais
  possibles, plus une recherche d'itinéraire multi-saut (fewest-hops) entre deux
  destinations.
- ~~Pas de conteneurisation de ce front~~ — **corrigé** : `Dockerfile` (build Node 22 +
  runtime `nginx-unprivileged:1.27-alpine` non-root par défaut, image inhabituelle par
  rapport aux 3 services Spring Boot mais nécessaire — pas de JVM ici) et fragment
  Compose statique `docker-compose.admin-dashboard.yml`, même mécanisme d'inclusion
  que les 3 services (chemin absolu, rôle `compose-assembly`). Seul le navigateur
  appelle identity/payment/travel-service (pas de SSR) : ce conteneur n'a donc besoin
  que de `backend-net` pour être routé par Traefik (`Host(\`localhost\`)`), pas de
  `data-net`. Les URLs d'API restent figées à la compilation dans `environment.ts`
  (voir Authentification/Configuration ci-dessus) : changer de déploiement suppose de
  reconstruire l'image, il n'existe pas de substitution d'env au démarrage du
  conteneur. Vérifié manuellement bout-en-bout (build, 2 replicas healthy, routage
  TLS via Traefik, fallback SPA, cache immuable des assets hashés) ; `ng serve` sur
  `http://localhost:4200` reste disponible pour le développement au quotidien.
- Pas de refresh token (voir Authentification) ; pas de vérification de signature du JWT
  côté front (voulu).
- Accessibilité : rôles/labels ARIA soignés (graphiques focalisables au clavier avec table
  équivalente, étoiles décoratives + texte), mais **aucun audit** (lecteur d'écran, contrastes
  mesurés) n'a été mené.
