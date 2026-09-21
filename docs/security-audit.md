# Let's Travel — Audit de sécurité (Phase 10)

> Document de **preuves**, pas de promesses : chaque affirmation renvoie à un fichier
> ou à un test qui la démontre, et ce qui n'est **pas** couvert est dit (§9).
> Il répond aux lignes « Sécurité » de `sujet/lets-travel-audit.md` (accès par rôle,
> SSL/TLS, données sensibles et identifiants, injection SQL, XSS, mots de passe chiffrés,
> conformité données personnelles) et au §4 du sujet. Méthode : lecture du code des trois
> services + du dashboard + des rôles Ansible, `grep` des constructions de requêtes,
> tests d'intégration Testcontainers pour tout ce qui est démontrable à faible coût.
> Décision de conception associée : `docs/lets-travel-architecture-decisions.md`, §1
> (addendum « Bootstrap et création d'ADMIN ») et §10.

**Résultat en une phrase.** La vérification a trouvé **un vrai trou**, corrigé
(n'importe qui pouvait s'auto-créer un compte `ADMIN` par `POST /users`), puis **trois
manques hors d'identity-service** (G1 transports, G2 capture PayPal, G3 placeholders
non fail-fast), le détail de `/actuator/health` (G6) et l'absence de limitation de
tentatives sur `/login` (G4), **tous corrigés dans le second passage de durcissement**
(preuves ci-dessous) ; restent les manques listés en §9, dits tels quels.

## 0. Le trou trouvé et corrigé : élévation de privilège par `POST /users`

- **Avant.** `POST /users` est public (inscription) et acceptait `role: ADMIN` ; un `role`
  omis donnait `ADMIN` (code **et** `DEFAULT 'ADMIN'` en base). Un anonyme obtenait donc
  un administrateur : lecture/suppression de tous les comptes, décision sur les
  signalements, forçage du statut de n'importe quel paiement, etc.
- **Après.** Sans jeton ADMIN valide : seuls `TRAVELER`/`TRAVEL_MANAGER` (défaut
  `TRAVELER`), `ADMIN` → **403**. Avec jeton ADMIN : tous les rôles. Le premier admin est
  créé au démarrage depuis `BOOTSTRAP_ADMIN_EMAIL`/`BOOTSTRAP_ADMIN_PASSWORD` (Vault →
  Ansible → `.env` → Compose), seulement s'il n'existe aucun ADMIN actif. `DEFAULT 'ADMIN'`
  supprimé (`V6__drop_role_default.sql`).
- **Code** : `UserService#create`, `AuthService#isAdmin`, `UserController#create`,
  `config/BootstrapAdminInitializer`.
- **Preuves** (`services/identity-service/src/test/java/com/travelplan/identity/`) :
  `AdminCreationPrivilegeIntegrationTest` (ADMIN public → 403 et aucune ligne créée ;
  jeton illisible/mal signé/expiré/d'un admin supprimé/d'un traveler ou manager → 403 ;
  jeton admin → 201 ; rôle omis → `TRAVELER` ; `INSERT` sans rôle refusé par la base),
  `BootstrapAdminIntegrationTest` (admin présent au démarrage, hash BCrypt, login, admin
  routes, idempotence, secrets absents des logs), `BootstrapAdminInitializerTest`
  (fail-fast : une seule variable, email invalide, mot de passe < 12 caractères).

## 1. Injection SQL (et Cypher, et Elasticsearch)

**Position.** Aucune requête n'est construite par concaténation d'une valeur venant de
l'utilisateur ; toutes les valeurs passent en **paramètres liés**.

**Recherche exhaustive** (`grep` sur `services/*/src/main`) :

| Service | Accès données | Constat |
|---|---|---|
| identity (PostgreSQL) | méthodes dérivées Spring Data + 5 `@Query` JPQL | tous en paramètres nommés (`:id`, `:reportedUserId`) ; **aucun** SQL natif, aucun `createNativeQuery`, aucune concaténation (`UserRepository`, `ReportRepository`) |
| payment (PostgreSQL) | méthodes dérivées + `@Query` JPQL (dont un `@Modifying`) | paramètres nommés uniquement ; aucun SQL natif (`PaymentRepository`) |
| travel (Neo4j) | SDN + `Neo4jClient` avec Cypher explicite | requêtes = constantes `"""…"""` avec `$param` liés (`.bindAll(params)`). Seule composition de chaînes : `SubscriptionRepository` assemble des **fragments constants** entre eux (`" + IS_LIVE + "`, `RETURN_VIEW_COLUMNS.formatted("s.status")` : arguments = littéraux du code) — jamais un paramètre de requête HTTP. `updatedCount(String query, …)` ne reçoit que ces constantes. |
| travel (Elasticsearch) | `RestClient` bas niveau | le corps JSON est un `Map` sérialisé par Jackson (`DestinationSearchService`), la saisie utilisateur n'est qu'une **valeur** de `multi_match`/`prefix` : pas de JSON assemblé à la main, donc pas d'injection de clause. |

**Preuves exécutables** (`SecurityEvidenceIntegrationTest`, identity, PostgreSQL réel) :
- `sqlInjectionPayloadsInLoginAreRejectedWithoutTouchingTheUsersTable` : `' OR '1'='1`
  comme email → **400** (Bean Validation, jamais jusqu'au SQL) ; emails *syntaxiquement
  valides* portant du SQL (`a'OR'1'='1'--@example.com`, `x'||'y@example.com`, …) → **401
  identique** à un email inconnu (pas de 500, pas de connexion) ; `' OR '1'='1` comme mot
  de passe d'un vrai compte → 401 ; le nombre de lignes de `users` est inchangé.
- `aSqlPayloadStoredAsAnEmailIsStoredAndReadBackAsPlainData` : la charge est stockée et
  relue **comme donnée** (comparaison exacte en base).
- `sqlAndScriptPayloadsInAReportReasonAreStoredVerbatimAndTheTableSurvives` :
  `'); DROP TABLE reports; --` dans un signalement → 201, texte intact, table présente.
- Le commentaire de feedback côté travel : `FeedbackIntegrationTest#commentIsStoredAsPlainTextNeverInterpreted`.

**Non couvert.** Pas de test d'injection Cypher automatisé sur chaque endpoint de
travel-service (seule la revue de code et le test de feedback ci-dessus) ; pas de scan
outillé (SAST/DAST) dans ce dépôt.

## 2. XSS

**Position** (ADR §5ter.3) : le backend renvoie du **texte brut en `application/json`**
sans le « nettoyer » ; l'échappement est fait **à l'affichage** par Angular
(interpolation `{{ }}` échappée par défaut).

- **Recherche dans le dashboard** (`grep` sur `services/admin-dashboard/src`) :
  `innerHTML`, `outerHTML`, `bypassSecurityTrust*`, `DomSanitizer`, `document.write`,
  `insertAdjacent*`, `eval(` → **0 occurrence**. Aucun contournement du sanitizer.
- Preuve backend : `sqlAndScriptPayloadsInAReportReasonAreStoredVerbatimAndTheTableSurvives`
  (`<script>` stocké tel quel, `Content-Type` JSON) ; `FeedbackIntegrationTest#commentIsStoredAsPlainTextNeverInterpreted`.
- Preuve d'affichage : `services/admin-dashboard/e2e/reports.spec.ts` (motif contenant
  `<b>gras</b>` affiché littéralement). **Écrit mais non exécuté ici** (la stack Docker est
  requise) — cette preuve-là n'est donc pas rejouée dans cette phase.

**Non couvert.** Aucun en-tête de sécurité HTTP (`Content-Security-Policy`,
`X-Content-Type-Options`, `X-Frame-Options`) n'est émis ni par les services ni par
Traefik : la défense repose sur l'échappement d'Angular seul, sans seconde ligne (G7).
Le jeton JWT est en `localStorage` (`core/auth/auth.service.ts`) : une XSS, si elle
existait, l'exfiltrerait (G8).

## 3. Mots de passe

- **Hachage** : `BCryptPasswordEncoder` (`identity/config/SecurityConfig`), coût par défaut
  10, sel aléatoire par hachage. Seul `password_hash` est stocké (`V2__add_password.sql`).
  Le mot de passe en clair ne quitte pas le DTO (`CreateUserRequest`) et n'atteint jamais
  le dépôt.
- **Preuves** (`SecurityEvidenceIntegrationTest`) :
  `passwordsAreStoredAsSaltedBcryptHashesNeverAsPlaintext` (lecture directe de la colonne :
  format `^\$2[aby]\$\d{2}\$…{53}$`, deux mots de passe identiques → deux hashs différents,
  `matches()` vrai, aucun autre champ ne contient le clair) ;
  `neitherThePlaintextPasswordNorItsHashAppearsInAnyResponse` (ni le clair, ni le hash, ni
  `password_hash` dans les réponses de création, login, `/me`, `GET /users`,
  `GET /users/{id}`, login échoué, 409 ; ni dans la charge utile du JWT).
  `AuthIntegrationTest#wrongPasswordAndUnknownEmailReturnTheExactSameResponse`.
- **Anti-énumération au login** : un email inconnu exécute quand même une comparaison BCrypt
  (`AuthService`, `dummyHash`) : même statut, même corps, même durée.
- **Bootstrap** : mot de passe admin ≥ 12 caractères, haché comme les autres, jamais loggué
  (`BootstrapAdminIntegrationTest#ifEveryAdminIsGoneTheNextStartupRecreatesOneAndNeverLogsTheSecrets`).

**Non couvert.** Longueur minimale 8 seulement, aucune règle de complexité, pas de liste de
mots de passe compromis ; **aucun endpoint de changement/réinitialisation de mot de
passe** (G5).

**Limitation des tentatives sur `/login` (G4, corrigé).** `service/LoginThrottle` : deux
compteurs d'échecs en fenêtre glissante (email normalisé, IP cliente), en mémoire et bornés ;
au seuil (défauts 5 / email, 50 / IP, 900 s ; env `LOGIN_THROTTLE_*`) → **429 + `Retry-After`**
avant toute lecture en base ou BCrypt ; un succès remet à zéro l'email ; un email inconnu est
compté et verrouillé comme un email connu (le `dummyHash` reste en place). Preuves :
`LoginThrottleIntegrationTest` (N échecs puis 429 même avec le bon mot de passe, casse
ignorée ; autre email inaffecté ; succès qui remet à zéro ; email inconnu traité à
l'identique — mêmes 401 puis même 429/corps/`Retry-After` ; IP verrouillée par credential
stuffing sur des emails distincts), `LoginThrottleTest` (fenêtre glissante avec horloge
contrôlée, normalisation, borne mémoire, IP : seul le dernier `X-Forwarded-For` est lu, et
seulement si `LOGIN_THROTTLE_TRUST_FORWARDED_FOR=true`). **Limites assumées** (ADR §10,
addendum G4) : état **par réplique** (N répliques = jusqu'à N fois le budget, oublié au
redémarrage), verrouillage volontaire d'un compte par un tiers possible (≤ 1 fenêtre),
entrées évinçables par un flot d'emails aléatoires, pas de CAPTCHA ; un limiteur de débit
au bord (Traefik) reste un complément utile, non fait.

## 4. JWT et gestion des secrets

**JWT.** HS256, 15 min, sujet = id utilisateur, claims `email` et `role`
(`identity/service/JwtService`). Chaque service a sa propre validation (duplication
voulue, `CLAUDE.md`).
- **Preuve de robustesse** (`SecurityEvidenceIntegrationTest#forgedExpiredAndTamperedTokensAreAllRejectedWith401`) :
  `alg=none`, mauvaise clé, token expiré, payload réécrit en `role=ADMIN` avec signature
  conservée, et jeton de service `service:identity` → **401** sur `/me` et `/users`.
  `AdminCreationPrivilegeIntegrationTest#aSoftDeletedAdminsStillUnexpiredTokenNoLongerCreatesAdmins` :
  identity re-vérifie que l'utilisateur est **actif** à chaque requête.
- **Jetons de service** : `service:identity` (→ payment, uniquement `DELETE /payments/by-user/*`),
  `service:payment` (→ travel, uniquement `/internal/subscriptions/*/payment-result`) ;
  sans claim `role`, donc refusés partout ailleurs, et symétriquement aucun jeton
  utilisateur (ADMIN compris) n'est accepté sur l'endpoint interne
  (`PaymentUserOwnershipIntegrationTest#serviceTokenRejectedOnOtherEndpoints`,
  travel `SubscriptionPaymentIntegrationTest#onlyThePaymentServiceTokenMayReportAPaymentResult`).

**Secrets.**
- Circuit : Vault (KV v2) → rôle Ansible `app-secrets` (seul propriétaire de
  `/opt/travel-plan/.env`, mode `0600`, `.env` ignoré par git) → interpolation Compose
  (`${VAR:?…}`) → variables d'environnement du conteneur. Aucun secret dans les fragments
  Compose ni dans le code : `grep` des `application.yml` et des `docker-compose.*.yml` — les
  seuls défauts sont non secrets (`CORS_ALLOWED_ORIGINS`, `SERVER_PORT`, `*_REPLICAS`, TTL,
  hôte/port Elasticsearch) et les deux variables **optionnelles** du bootstrap.
- Cloisonnement : 3 policies Vault par service (`identity-policy`, …) ; le bootstrap admin
  vit dans `secret/identity/*`, donc lisible par identity seule.
- **Fail-fast prouvé** : `ConfigFailFastTest` (identity) — sans variable, chaque secret/URL
  est irrésolvable. Cette preuve a révélé un défaut réel, corrigé : en Spring,
  `${VAR:?msg}` (syntaxe Compose) n'est **pas** fail-fast, le texte après `:` est une valeur
  par défaut littérale ; identity l'employait pour `PAYMENT_SERVICE_URL` (et `DB_*`).
  payment-service avait la même syntaxe pour `DB_*` et travel-service pour `NEO4J_*` :
  **corrigé (G3)**, chaque service a désormais son `ConfigFailFastTest` (payment : les 10
  secrets/URLs + chaque `DB_*` isolément ; travel : `NEO4J_*` isolément, `JWT_SIGNING_KEY`,
  `PAYMENT_SERVICE_URL`). Les suites de tests fournissent toutes les variables requises
  (`@DynamicPropertySource` + `src/test/resources/application.properties`).
- Journaux : JSON structuré, `requestId`, méthode + chemin + statut (`RequestIdFilter`) ;
  aucun mot de passe, jeton, en-tête `Authorization` ni corps de requête n'est journalisé
  (lecture des `log.*` des trois services) ; l'URL JDBC est loggée **sans** mot de passe
  (`DataSourceConfig`).
- `GET /actuator/health` exposait des détails (base, disque) à un anonyme : `show-details`
  passé à `never` (identity ; test `theHealthEndpointStaysUpButExposesNoInternalDetails`),
  puis **aussi dans payment et travel (G6, corrigé)** : `PaymentServiceApplicationTests#actuatorHealthReportsUp`
  et `TravelServiceApplicationTests#actuatorHealthReportsUpWithoutExposingInternalDetailsToAnAnonymousCaller`
  (statut `UP`, aucun `components`), `ConfigFailFastTest#healthDetailsAreNeverShownToAnonymousCallers`.
  Les sondes ne lisent que le code HTTP (`curl -fsS` dans `docker-compose.payment.yml`/
  `docker-compose.travel.yml`, `httpGet` dans `k8s/3*.yaml`) : elles ne sont pas affectées.

**Non couvert.** La clé HS256 est **partagée** par les trois services (compromission d'un
service = falsification de jetons pour tous) ; pas de rotation, pas de révocation, pas de
refresh token ; Vault en **mode dev** (en mémoire, root token de dev, placeholders
`changeme_*` dans `vault/defaults/main.yml` — dont le mot de passe admin de bootstrap, à
surcharger via ansible-vault hors poste local).

## 5. SSL/TLS

- **Au bord** : Traefik est le seul point d'entrée, uniquement `websecure` (HTTPS) ; **aucun
  entrypoint HTTP :80** (`ansible/roles/traefik/templates/traefik.static.yml.j2`), certificat
  fourni par fichier (`traefik.dynamic.tls.yml.j2`), routeurs `tls=true` sur chaque service
  (`services/*/docker-compose.*.yml`), aucun port applicatif publié vers l'hôte.
- Preuve exécutable **non rejouée ici** (exige la stack) :
  `curl -vk https://identity.localhost/actuator/health` (poignée de main TLS, certificat),
  et `curl http://identity.localhost` doit échouer (rien n'écoute sur :80).

**Non couvert / honnêtement limité.** Certificat **auto-signé** de développement (pas de
chaîne de confiance) ; version TLS minimale et suites de chiffrement **non fixées** dans la
config (valeurs par défaut de Traefik, non vérifiées ici) ; pas de HSTS ; le trafic
**interne** (Traefik → services, services → services, services → PostgreSQL/Neo4j/
Elasticsearch) est en **clair** sur des réseaux Docker cloisonnés (`backend-net`,
`data-net`) : choix assumé de la Phase 0, à signaler pour la donnée « en transit » (G9).
`CLAUDE.md` parle de ForwardAuth Traefik : il **n'est pas en place** (voir le README du rôle
`traefik`) — l'authentification est faite dans chaque service, pas au bord.

## 6. Contrôle d'accès par rôle (matrice endpoint × rôle)

Dérivée de la lecture des contrôleurs et de `TokenValidationService`/`AuthService` de chaque
service. Légende : ✔ autorisé · ✘ refusé (403 ; 401 sans jeton valide) · **own** = seulement
ses propres ressources (identifiant pris dans le `sub` du JWT, jamais dans le corps) ·
« Test » = test qui le démontre (— = non couvert par un test dédié, ne repose que sur la
lecture du code).
`ADMIN` ⊃ `TRAVEL_MANAGER` ⊃ `TRAVELER` (hiérarchie du sujet) sauf indication contraire.

### identity-service (`services/identity-service/src/test/java/com/travelplan/identity/`)

| Endpoint | Anonyme | TRAVELER | TRAVEL_MANAGER | ADMIN | Test |
|---|---|---|---|---|---|
| `POST /users` (TRAVELER/MANAGER) | ✔ | ✔ | ✔ | ✔ | `RoleAssignmentIntegrationTest` |
| `POST /users` (`role: ADMIN`) | ✘ 403 | ✘ | ✘ | ✔ | `AdminCreationPrivilegeIntegrationTest` |
| `POST /login` | ✔ | ✔ | ✔ | ✔ | `AuthIntegrationTest` |
| `GET /me` | ✘ | ✔ | ✔ | ✔ | `JwtIntegrationTest` |
| `GET /users`, `GET/PATCH/DELETE /users/{id}` | ✘ | ✘ | ✘ | ✔ | `UsersAuthorizationIntegrationTest#everyAdminOnlyRouteRefusesATravelerAndATravelManagerWith403` |
| `POST /reports` | ✘ | ✔ own reporter | ✔ | ✔ | `ReportsIntegrationTest` |
| `GET /reports` | ✘ | ✘ | ✘ | ✔ | `ReportsIntegrationTest#getReportsWithoutAdminRoleReturns403` |
| `PATCH /reports/{id}/status` | ✘ | ✘ | ✘ | ✔ | `ReportsIntegrationTest#updateStatusWithoutAdminRoleReturns403` |
| `GET /reports/count/{userId}` | ✘ | ✔ | ✔ | ✔ | `ReportsIntegrationTest#countByReportedUserIsAccessibleToAnyAuthenticatedRole…` |
| `GET /actuator/health` | ✔ (statut seul) | | | | `SecurityEvidenceIntegrationTest#theHealthEndpointStays…` |

### payment-service (`…/payment-service/src/test/java/com/travelplan/payment/`)

| Endpoint | Anonyme | TRAVELER | TRAVEL_MANAGER | ADMIN | Test |
|---|---|---|---|---|---|
| `POST /payments`, `/payments/stripe`, `/payments/paypal` | ✘ | own (`userId` = `sub`) | own | ✔ tous | `PaymentRbacOwnershipIntegrationTest#travelerCannotCreateAPaymentForAnotherUser` |
| `GET /payments/{id}` | ✘ | own (sinon 404) | own | ✔ | `…#travelerCannotReadAnotherTravelersPayment` |
| `GET /payments` | ✘ | own seulement | own | ✔ tous | `…#listPaymentsIsScopedToTheCallerUnlessAdmin` |
| `GET /payments/summary` | ✘ | own | own | ✔ `?userId=` | `SubscriptionPaymentIntegrationTest#aTravelerCannotReadAnotherUsersSummaryButAnAdminCan` |
| `PATCH /payments/{id}/status` | ✘ | ✘ | ✘ | ✔ | `PaymentRbacOwnershipIntegrationTest#onlyAdminCanForceAPaymentsStatus` |
| `DELETE /payments/{id}` | ✘ | own | own | ✔ | — (`PaymentService#delete`, même filtre propriétaire) |
| `POST /payments/reconcile-subscriptions` | ✘ | ✘ | ✘ | ✔ | `SubscriptionPaymentIntegrationTest#onlyAnAdminMayTriggerReconciliation` |
| `DELETE /payments/by-user/{userId}` | ✘ | ✘ | ✘ | ✔ ou jeton `service:identity` | `PaymentUserOwnershipIntegrationTest#deleteByUserAcceptsServiceToken` |
| `POST /payments/paypal/{orderId}/capture` | ✘ | own (sinon 404, comme un order inconnu) | own | ✔ | `PayPalCaptureOwnershipIntegrationTest` (owner OK, autre user/manager 404 sans appel PayPal, admin OK, inconnu 404), `PayPalPaymentServiceTest#captureOrder_masksAnotherUsersPaymentAsNotFound_andNeverCallsPayPal` |
| `POST /webhooks/stripe` | pas de JWT : **signature HMAC Stripe** vérifiée | | | | `StripeWebhookIntegrationTest#rejectsInvalidSignature` |

### travel-service (`…/travel-service/src/test/java/com/travelplan/travel/`)

| Endpoint | Anonyme | TRAVELER | TRAVEL_MANAGER | ADMIN | Test |
|---|---|---|---|---|---|
| `GET /destinations`, `/{id}`, `/search`, `/autocomplete`, `/{id}/transports` | ✘ | ✔ | ✔ | ✔ | `TravelCatalogueRbacIntegrationTest#travelerCanListDestinations` |
| `POST /destinations` | ✘ | ✘ | own (`managerId` = `sub`) | ✔ | `…#travelerCannotCreateADestination`, `…#travelManagerCannotCreateADestinationInSomeoneElsesName` |
| `PUT/DELETE /destinations/{id}` | ✘ | ✘ | own | ✔ | `TravelOwnershipIntegrationTest` (`anotherManagerCannotUpdate…/Delete…`) |
| `POST /destinations/{fromId}/transports` | ✘ | ✘ | own (origine ; la cible n'a pas à l'être) | ✔ | `TransportOwnershipIntegrationTest` (propriétaire OK, autre manager 403 — même avec cible inconnue —, admin OK, traveler 403, origine/cible inconnue 404) |
| `POST /destinations/{id}/subscriptions`, `DELETE …/subscriptions` (soi-même), `GET /travelers/me/subscriptions` | ✘ | own | own | own | `SubscriptionIntegrationTest` |
| `GET /destinations/{id}/subscriptions`, `DELETE …/subscriptions/{travelerId}` | ✘ | ✘ | own destination | ✔ | `SubscriptionIntegrationTest#anotherManagerCannotListSubscribersForSomeoneElsesDestination`, `…#anotherManagerCannotForceUnsubscribe…` |
| `POST /destinations/{id}/feedback`, `GET /travelers/me/feedback` | ✘ | own (a participé) | own | own | `FeedbackIntegrationTest` |
| `GET /destinations/{id}/feedback` | ✘ | ✘ | own destination | ✔ | `FeedbackIntegrationTest#anotherManagerCannotSeeFeedbackOfSomeoneElsesDestination` |
| `GET /feedback` | ✘ | ✘ | ✘ | ✔ | `FeedbackIntegrationTest#onlyAdminsCanListAllFeedback` |
| `GET /managers/{id}/stats` | ✘ | ✔ | ✔ | ✔ | `FeedbackIntegrationTest#managerStatsAreOpenToEveryKnownRoleButNotToAnonymous` |
| `GET /managers/ranking` | ✘ | ✘ | ✘ | ✔ | `FeedbackIntegrationTest#rankingIsAdminOnly` |
| `POST /internal/subscriptions/{ref}/payment-result` | ✘ | ✘ | ✘ | ✘ | jeton `service:payment` seul : `SubscriptionPaymentIntegrationTest#onlyThePaymentServiceTokenMayReportAPaymentResult` |

Notes de lecture. (a) payment et travel **ne consultent pas la table `users`** : un jeton
reste accepté jusqu'à son expiration (15 min) même si l'utilisateur a été supprimé ; seule
identity re-vérifie que le compte est actif (G10). (b) Les routes `/internal/*` et
`/webhooks/*` sont joignables depuis l'extérieur (Traefik route par `Host` seulement) :
elles ne sont protégées que par leur jeton de service / signature, pas par le réseau (G9).

## 7. Données personnelles et vie privée

**Ce qui est stocké** (lecture des entités et migrations) :

| Service | Donnée | Nature |
|---|---|---|
| identity | `users.email`, `password_hash` (BCrypt), `role`, `created_at`, `deleted_at` | identifiant direct (email) |
| identity | `reports` : `reporter_id`, `reported_user_id`, `reason` (texte libre ≤ 2000), `status` | texte libre visible des seuls admins |
| payment | `payments` : `user_id`, `amount`, `currency`, `provider`, `status`, `external_reference` (id Stripe/PayPal), `travel_id`, `subscription_ref` | métadonnées de paiement ; **aucun numéro de carte ni CVV** — la saisie de la carte est faite chez Stripe/PayPal, ce service ne reçoit qu'un identifiant de transaction |
| travel | `TravelerRef {userId}`, relations `SUBSCRIBED` (statut, dates, montant, devise), `GAVE_FEEDBACK` (note, commentaire libre), `managerId` | pseudonymes (UUID), pas d'email ni de nom : identity reste la seule source d'identité |
| journaux | méthode, chemin (contient parfois un UUID), statut, durée, `requestId` | pas d'email, pas de corps, pas de jeton |

**Réponses aux principes RGPD, honnêtement.**
- *Minimisation* : pas de nom, adresse, téléphone ni date de naissance ; l'email est la seule
  donnée directement identifiante.
- *Sécurité du stockage* : mots de passe hachés (§3), secrets hors du code (§4).
- *Accès* : un utilisateur ne lit que ses propres paiements, abonnements, avis (§6) ; les
  emails ne sont visibles que d'un `ADMIN`.
- **Droit à l'effacement : PARTIELLEMENT satisfait, et il faut le dire.** Le projet est en
  *soft-delete partout* : `DELETE /users/{id}` pose `deleted_at` mais **conserve la ligne**,
  donc l'email et le hash restent en base ; le cascade vers payment est aussi un soft-delete
  (best-effort, sans reprise : si payment est indisponible, les paiements restent) ; les
  nœuds/relations Neo4j (`TravelerRef`, abonnements, avis) ne sont **pas** supprimés à la
  suppression du compte. Ce n'est donc pas un effacement au sens de l'art. 17 RGPD mais un
  **retrait d'accès**. (Tenable côté comptabilité pour les paiements — obligation légale de
  conservation — mais rien ne documente une purge ni une anonymisation ici.)
- Non fournis : export des données personnelles (portabilité), consentement/mentions
  légales/bandeau cookies, durée de conservation, registre des traitements, procédure de
  notification de violation. Ce sont des manques **de conformité**, pas de code (G11).

## 8. Comment rejouer les preuves

```bash
# identity-service : 85 tests dont toutes les preuves ci-dessus (Testcontainers PostgreSQL)
cd services/identity-service && ./mvnw test
# payment-service : 64 tests (3 ignorés : sandbox PayPal/Stripe réel) ; travel-service : 155 tests (Neo4j)
cd services/payment-service && ./mvnw test
cd services/travel-service && ./mvnw test
# Recherches (aucune concaténation de requête, aucun contournement du sanitizer Angular)
grep -rnE 'nativeQuery|createNativeQuery' services/*/src/main
grep -rnE 'innerHTML|bypassSecurityTrust' services/admin-dashboard/src
# Bord TLS (nécessite la stack Docker, non rejoué dans cette phase)
curl -vk https://identity.localhost/actuator/health
```

## 9. Manques connus

Sévérité : **H** = à corriger avant toute exposition réelle ; **M** = à planifier ;
**B** = dette assumée. « Propriétaire » = qui doit agir. **G1-G4 et G6 sont corrigés** (second
passage de durcissement, preuves dans les sections citées) : leur ligne reste pour la
traçabilité, marquée ✔. Tout le reste est **ouvert**.

| # | Sév. | Manque | Où | Propriétaire |
|---|---|---|---|---|
| G1 ✔ | **M/H** | ~~`POST /destinations/{fromId}/transports` : tout `TRAVEL_MANAGER` pouvait relier n'importe quelle destination.~~ **Corrigé** : propriété de l'origine exigée (ou `ADMIN`), la cible doit seulement exister (ADR §10, addendum G1). Preuve : `TransportOwnershipIntegrationTest` (§6). | travel | fait |
| G2 ✔ | **M/H** | ~~`POST /payments/paypal/{orderId}/capture` sans contrôle de propriétaire.~~ **Corrigé** : propriétaire ou `ADMIN`, sinon 404 identique à un order inconnu, avant tout appel PayPal (ADR §10, addendum G2). Preuve : `PayPalCaptureOwnershipIntegrationTest` (§6). | payment | fait |
| G3 ✔ | M | ~~`${VAR:?msg}` non fail-fast dans `application.yml` (payment `DB_*`, travel `NEO4J_*`).~~ **Corrigé** en `${VAR}`, avec un `ConfigFailFastTest` par service (§4). | payment, travel | fait |
| G4 ✔ | M | ~~Aucune limitation de tentatives sur `POST /login`.~~ **Corrigé** en service (`LoginThrottle`, 429 + `Retry-After`) ; **limites assumées** : état par réplique, verrouillage volontaire possible, pas de limiteur de débit au bord (§3, ADR §10 addendum G4). | identity | fait, complément Traefik à faire |
| G5 | M | Aucun endpoint de changement ni de réinitialisation de mot de passe ; rotation du mot de passe bootstrap = Vault + suppression (soft) de la ligne admin (ADR §1 addendum). | identity | à faire |
| G6 ✔ | B | ~~`show-details: always` dans payment et travel.~~ **Corrigé** : `never` dans les trois services (§4). | payment, travel | fait |
| G7 | M | Aucun en-tête de sécurité HTTP (CSP, `X-Content-Type-Options`, `X-Frame-Options`, HSTS). | bord (Traefik) / front | à faire |
| G8 | B | Jeton en `localStorage` (vol par XSS) ; pas de cookie `HttpOnly`. | front | assumé (Phase 8) |
| G9 | B | Trafic interne et vers les bases en clair ; certificat auto-signé ; TLS min. et suites non fixés ; pas de HSTS ; `/internal/*` et `/webhooks/*` joignables via le routeur public (protégés par jeton/signature seulement). | infra | assumé Phase 0 / à durcir |
| G10 | B | Jetons acceptés 15 min après suppression du compte par payment/travel (pas d'accès à `users`) ; pas de révocation, pas de refresh ; clé HS256 partagée entre les trois services. | tous | assumé |
| G11 | M | Conformité données personnelles : pas d'effacement physique/anonymisation, pas d'export, pas de mentions légales/consentement, pas de politique de conservation (§7). | tous | à décider (produit/juridique) |
| G12 | B | Emails non normalisés (casse) : `A@x.com` et `a@x.com` sont deux comptes ; `POST /users` répond 409 sur un email déjà pris (**énumération de comptes** possible à l'inscription — le login, lui, n'énumère pas). | identity | assumé |
| G13 | B | Vault en mode dev (en mémoire, root token de dev) et placeholders `changeme_*` dont le mot de passe admin de bootstrap ; écran admin « Utilisateurs » du front crée sans rôle (donc `TRAVELER`). | infra, front | à surcharger / à compléter |
| G14 | B | Pas d'audit de dépendances (CVE), de SAST/DAST ni de test d'intrusion dans le CI. | CI | à faire |
