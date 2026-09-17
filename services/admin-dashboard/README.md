# admin-dashboard

Front d'administration **Angular 22** : application standalone, routes lazy-loaded,
signals, Tailwind 4, tests Vitest.

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
Traefik être accepté par le navigateur.

## Écrans

Quatre écrans, tous réellement câblés à un backend (`src/app/features/`) :

| Route | Écran | Opérations câblées |
|---|---|---|
| `/login` | Connexion | `POST /login` ; le JWT retourné est stocké en `localStorage` |
| `/users` | Utilisateurs | Liste (`GET /users`), création (email + mot de passe), modification de **l'email seulement** (`PATCH /users/{id}`), suppression (`DELETE /users/{id}`) |
| `/payments` | Paiements | Liste (`GET /payments`), création (montant + devise), transition de statut `PENDING → COMPLETED`/`FAILED` (`PATCH /payments/{id}/status`), suppression |
| `/destinations` | Destinations | Liste, création, suppression ; sous-vue par ligne listant les `TRANSPORT` **sortants** (`GET /destinations/{id}/transports`) et formulaire de création d'un transport 1-hop (`POST /destinations/{fromId}/transports`) |

Détails vérifiables dans le code :

- À la création d'un paiement, le `userId` n'est **pas** un champ de formulaire : il
  est lu dans la claim `sub` du JWT stocké (`AuthService.getCurrentUserId()`).
- La liste des modes de transport (`TRAIN`, `PLANE`, `BUS`, `CAR`, `BOAT`) est un
  miroir de la liste fermée imposée par `travel-service`, pas un enum appartenant au
  front.
- Le select de destination cible exclut la destination courante : l'UI n'offre jamais
  une boucle sur soi-même (le backend la rejette aussi en 400).
- Après chaque mutation réussie, l'écran **recharge la liste depuis le serveur** plutôt
  que de muter le signal local.

## Structure

```
src/app/
  core/auth/          AuthService (JWT en localStorage, décodage de la claim sub)
  core/guards/        authGuard
  core/interceptors/  authInterceptor
  core/theme/         ThemeService
  features/           login, users, payments, destinations (+ leurs services HTTP)
  shared/layout/      AppShellComponent (nav + bascule de thème)
  shared/ui/          alert, button, card, input
```

- `/login` est hors du layout ; les 3 routes métier sont des enfants de
  `AppShellComponent` et protégées par `authGuard`.
- **Composants réutilisables** sous `shared/ui/` (`app-alert`, `app-button`,
  `app-card`, `app-input`) : les 3 écrans métier les utilisent au lieu de redéclarer
  leurs propres champs, boutons et bandeaux d'erreur.

## Authentification (état réel)

- `AuthService` conserve le JWT dans `localStorage` (clé `admin-dashboard.jwt`) et
  l'expose via un signal.
- `authInterceptor` ajoute `Authorization: Bearer <token>` à toute requête dont l'URL
  commence par l'une des 3 URLs d'API, et **sur 401** : purge le token et redirige
  vers `/login`.
- **Pas de refresh token, pas de renouvellement silencieux** : le JWT backend dure
  15 min, à l'expiration l'utilisateur retombe sur `/login`.
- `AuthService.decodeJwtPayload()` ne fait **aucune vérification de signature** — il ne
  sert qu'à lire la claim `sub` d'un token que le backend re-vérifie à chaque requête.

## Thème clair / sombre

- `ThemeService` : signal `theme` (`'light' | 'dark'`) + `effect` qui bascule la classe
  `dark` sur `<html>` et persiste le choix dans `localStorage`
  (clé `admin-dashboard.theme`).
- La variante `dark:` de Tailwind est pilotée par cette classe.
- Un **script inline dans `index.html`** applique le thème persisté à `<html>` avant le
  bootstrap Angular, pour éviter un flash du mauvais thème au rechargement. C'est
  pourquoi `ThemeService` lit sa valeur initiale dans la classe de `<html>` et non dans
  `localStorage` : une seule source de vérité.
- La bascule est exposée par un bouton dans l'en-tête de `AppShellComponent`.

## Dette assumée : aucun contrôle de rôle, malgré le nom « admin »

Vérifiable dans `src/app/core/auth/auth.service.ts` et
`src/app/core/guards/auth.guard.ts` :

- Le JWT émis par `identity-service` **ne porte aucune claim de rôle**. `AuthService`
  n'expose que `isAuthenticated` (= « un token est stocké ») et `getCurrentUserId()`
  (claim `sub`). Il n'existe ni notion de rôle, ni profil « Admin », ni RBAC.
- `authGuard` ne teste **que la présence d'un token** : il ne lit aucune claim, ne
  compare rien.

Conséquence directe et non maquillée : **n'importe quel utilisateur authentifié — et
pas seulement un administrateur — a accès à la totalité de ce dashboard**, y compris
la liste des utilisateurs, leur modification et leur suppression. Le nom
« admin-dashboard » décrit une intention, pas une restriction d'accès effective.
Cette dette est côté backend d'abord (le token ne transporte pas de rôle) ; aucun
contrôle front ne pourrait la combler.

## Tests E2E (Playwright, contre le vrai backend)

`e2e/*.spec.ts` (login, auth-guard, destinations, payments) tournent sans mock,
contre le vrai `ng serve` (port 4200) et la vraie stack Docker Compose
(identity/payment/travel-service, Postgres, Neo4j, Vault, Traefik).

```bash
npm start        # ng serve, dans un terminal séparé
npm run e2e       # playwright test
```

**Dernière exécution confirmée : 2026-09-17, 5/5 tests passent** (stack Docker déjà up
depuis ~21h, `ng serve` déjà démarré depuis la veille — aucune anomalie d'environnement
rencontrée).

## Non implémenté

- **Pas de RBAC ni de profil Admin** (voir ci-dessus) : ni côté JWT, ni côté guard.
- **Pas de bouton de déconnexion** : `AuthService.logout()` existe mais n'est câblé à
  aucun contrôle du layout ; il n'est appelé que par l'interceptor sur 401.
- **Pas de modification des destinations** : `travel-service` expose bien
  `PUT /destinations/{id}`, mais l'écran n'offre que création, liste et suppression
  (`DestinationService` n'a pas de méthode `update`). Un commentaire de
  `destination.service.ts` affirme à tort que le backend n'expose aucun `PUT`.
- **Pas de mise à jour ni de suppression d'un `TRANSPORT`** : le backend ne les expose
  pas, le front ne les simule pas.
- **Pas de conteneurisation** : aucun `Dockerfile`, aucun fragment Compose, aucune
  route Traefik pour ce front. Il ne tourne aujourd'hui que via `ng serve` sur
  `http://localhost:4200` (origine autorisée par la config CORS des services).
- **Couverture de test unitaire quasi nulle** : côté Vitest, une seule spec
  (`src/app/app.spec.ts`), qui vérifie uniquement que le composant racine s'instancie.
  Aucun test unitaire des écrans, des services HTTP, du guard ni de l'intercepteur.
  La couverture fonctionnelle existe côté E2E (voir [Tests E2E](#tests-e2e-playwright-contre-le-vrai-backend)
  ci-dessus), mais reste limitée à 4 parcours (login, auth-guard, destinations,
  payments) — pas de couverture E2E pour l'écran utilisateurs ni pour le thème.
