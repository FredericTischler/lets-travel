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
| `/travels` | Catalogue + recherche Elasticsearch + autocomplétion | TRAVELER | `GET /destinations`, `/destinations/search?q=`, `/destinations/autocomplete?prefix=` |
| `/travels/:id` | Détail d'un voyage, s'inscrire / se désinscrire, signaler l'organisateur | TRAVELER | `GET /destinations/{id}`, `POST`/`DELETE /destinations/{id}/subscriptions`, `GET /travelers/me/subscriptions`, `POST /reports`, `GET /reports/count/{userId}` |
| `/my-subscriptions` | Mes abonnements : à venir, effectués, annulés + compteurs | TRAVELER | `GET /travelers/me/subscriptions` |
| `/manager/travels` | Mes voyages organisés : créer / modifier / supprimer (dates, prix, capacité, activités, hébergements) | TRAVEL_MANAGER | `GET`, `POST`, `PUT`, `DELETE /destinations` |
| `/manager/travels/:id/subscribers` | Abonnés d'un voyage + désinscription forcée | TRAVEL_MANAGER | `GET /destinations/{id}/subscriptions`, `DELETE /destinations/{id}/subscriptions/{travelerId}` |
| `/admin/reports` | File de modération des signalements (filtre par statut, `OPEN` → `REVIEWED`/`DISMISSED`/`ACTIONED`) | ADMIN | `GET /reports`, `PATCH /reports/{id}/status`, `GET /users` (emails, best-effort) |
| `/users` | Utilisateurs (liste avec rôle, création, modification de l'email, suppression) | ADMIN | `/users` |
| `/payments` | Paiements manuels (liste, création, transition de statut, suppression) | ADMIN | `/payments` |
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
`{label, route, roles}` et le shell n'affiche que les entrées permises au rôle courant
(même fonction `hasAccess` que les guards). Il affiche aussi l'email (claim `email`),
un badge de rôle, la bascule de thème et le bouton **Se déconnecter**
(`AuthService.logout()` puis `/login`). Ajouter un écran = une route gardée + une
entrée dans `nav-items.ts`.

**Responsive** : sous le breakpoint `lg` (1024 px) la navigation et les contrôles se
replient derrière un bouton « Menu » (`aria-expanded`), refermé au clic sur un lien.
Il n'y a qu'**un seul** `<nav>` dans le DOM (le CSS décide barre ou liste déroulante).
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
  features/           login, register, travels (traveler), subscriptions, manager, reports,
                      users, payments, destinations (admin + formulaire partagé)
  shared/layout/      AppShellComponent + nav-items.ts
  shared/ui/          alert, badge, button, card, input
  shared/http-error.ts  extraction du message d'erreur backend
e2e/                  specs Playwright + support/ (création de comptes/voyages par API)
```

Pattern d'un écran : `features/<nom>/*.component.ts|html` + `*.service.ts` (HTTP) +
`.spec.ts`. Composants réutilisables : `app-alert`, `app-badge` (statuts/rôles),
`app-button`, `app-card`, `app-input`, `app-destination-form`.

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
  (`docs/security-audit.md`). Conséquence : l'écran admin « Utilisateurs » crée un compte
  **sans rôle**, donc `TRAVELER` (plus `ADMIN` comme avant) — un sélecteur de rôle sur ce
  formulaire reste à faire.

## Thème clair / sombre

- `ThemeService` : signal `theme` (`'light' | 'dark'`) + `effect` qui bascule la classe
  `dark` sur `<html>` et persiste le choix dans `localStorage`
  (clé `admin-dashboard.theme`).
- Un **script inline dans `index.html`** applique le thème persisté avant le bootstrap
  Angular, pour éviter un flash du mauvais thème.

## Sécurité front (XSS)

Aucun `[innerHTML]`, aucun `bypassSecurityTrust*` dans le code : tout texte libre
(motif d'un signalement, noms de voyages, activités…) passe par l'interpolation Angular,
qui échappe. Des specs le vérifient (motif de signalement contenant du HTML, nom de voyage
contenant une balise).

## Tests

**Vitest** — `npm test` : 27 fichiers, 183 tests, tous verts. Couvrent : rôles/hiérarchie,
guards (`roleGuard`, `homeGuard`, fallbacks de rôle inconnu), `AuthService` (rôle, email,
inscription), shell et navigation par rôle (dont menu mobile et logout), règles du délai
de 3 jours, services HTTP (destinations, abonnements, signalements), et les composants
avec logique (formulaire de destination, catalogue avec debounce/503, détail voyage
avec ses cas 409, mes abonnements, mes voyages, abonnés, file de signalements,
inscription, connexion, destinations admin). `ng build` est vert.

**Playwright** (`npm run e2e`, contre le vrai backend, sans mock) — **les specs ci-dessous
ont été écrites mais NON exécutées dans cette phase** : la suite exige la stack Docker
complète, dont les images étaient périmées (construites avant les changements backend des
phases 2/3/6) et qui n'a volontairement pas été démarrée. Ils sont typés et listés par
Playwright (`playwright test --list` : 22 tests), rien de plus n'est garanti.

| Spec | Statut |
|---|---|
| `register.spec.ts` (inscription voyageur / organisateur, pas d'Admin, logout) | écrit, non exécuté |
| `role-navigation.spec.ts` (guards par rôle, navigation par rôle, menu mobile 375 px) | écrit, non exécuté |
| `subscription-flow.spec.ts` (inscription, désinscription, refus à moins de 3 jours, abonnés + désinscription forcée) | écrit, non exécuté |
| `manager-travels.spec.ts` (créer/modifier/supprimer, voyages des autres masqués, validation des dates) | écrit, non exécuté |
| `reports.spec.ts` (signalement puis décision admin, texte libre non interprété) | écrit, non exécuté |
| `travel-search.spec.ts` (autocomplétion + recherche si Elasticsearch est up, sinon repli + bandeau) | écrit, non exécuté |
| `destinations.spec.ts` | **modifié** (prix, capacité, organisateur désormais requis) — non ré-exécuté ; dernière exécution avant modification : 2026-09-17 |
| `login`, `auth-guard`, `payments` | inchangés ; dernière exécution confirmée 2026-09-17 (5/5 avec `destinations`), avant la Phase 8 |

Les specs créent leurs comptes par `POST /users`. `TRAVELER` et `TRAVEL_MANAGER` passent par
l'inscription publique ; **`ADMIN` ne le peut plus** (403 sans jeton admin, et un rôle omis
donne désormais `TRAVELER`, plus jamais `ADMIN`). `createTestUser(request)` (défaut `ADMIN`)
se connecte donc avec **l'admin de bootstrap** (créé au démarrage d'identity-service depuis
Vault `secret/identity/bootstrap-admin`) puis crée le compte avec son jeton. Ses identifiants
se passent par l'environnement : `E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... npm run e2e`
(placeholders de dev dans `ansible/roles/vault/defaults/main.yml`) ; le helper échoue avec un
message explicite s'ils manquent. Ce changement des specs est **typé et listé
(`playwright test --list`) mais non exécuté** : il faut la stack Docker complète.

## Non implémenté

- **Paiement d'un abonnement** : aucune UI de paiement côté voyageur (Stripe / PayPal /
  manuel). L'écran `/payments` reste l'écran d'administration des paiements manuels.
  Volontairement non branché : l'API de paiement d'abonnement n'était pas mergée.
- **Feedback** : ni saisie d'un avis/note après un voyage, ni affichage des avis (voyageur,
  organisateur, admin).
- **Page organisateur** (statistiques, notes passées, nombre de signalements) : seul le
  compteur de signalements est affiché sur le détail d'un voyage ; pas de page dédiée. Pas de
  profil voyageur non plus (l'API n'expose que l'id aux organisateurs).
- **Tableau de bord statistiques de l'organisateur** (revenus, nombre de voyages, de
  voyageurs) et **tableaux de bord admin** (revenus, meilleurs organisateurs/voyages,
  historique des voyages, classement des organisateurs) : non construits, aucun endpoint
  correspondant n'existe encore.
- **Recommandations Neo4j** (« suggestions personnalisées ») et statistiques personnelles
  complètes du voyageur (moyens de paiement préférés) : non branchés.
- **PWA** et **i18n** (bonus du sujet) : non faits. Les libellés sont en français,
  codés en dur dans les templates, sans infrastructure de traduction.
- **Trajets `TRANSPORT`** : gérés uniquement depuis l'écran admin `/destinations` (pas
  depuis l'écran organisateur), création et liste sortante seulement — le backend n'expose
  ni mise à jour ni suppression.
- **Pas de conteneurisation** de ce front (aucun `Dockerfile`, fragment Compose ni route
  Traefik) : il ne tourne que via `ng serve` sur `http://localhost:4200`.
- Pas de refresh token (voir Authentification) ; pas de vérification de signature du JWT
  côté front (voulu).
- Accessibilité : rôles/labels ARIA soignés sur la navigation, la recherche et les
  formulaires, mais **aucun audit** (lecteur d'écran, contrastes) n'a été mené.
