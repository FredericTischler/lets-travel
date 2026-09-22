# services/

Sommaire des déployables. Un dossier = un déployable indépendant. Les frontières de
contexte sont détaillées dans [`docs/architecture-decisions.md`](../docs/architecture-decisions.md) §1.

Les détails de chaque service sont dans **son propre README** ; ce fichier ne donne
que le statut réel et le point d'entrée.

| Dossier | Rôle | Base | Statut réel |
|---|---|---|---|
| [`identity-service/`](identity-service/README.md) | Comptes + authentification : 3 rôles (Admin/Travel Manager/Traveler), JWT HS256 + refresh token, CRUD users, changement de mot de passe, signalements, cascade de suppression vers `payment-service` | PostgreSQL `identity_db` | **Implémenté** : `POST /users` (rôle par défaut `TRAVELER`, `ADMIN` réservé à un `ADMIN` — voir ADR §1 addendum), `GET /users`, `GET /users/{id}`, `PATCH /users/{id}`, `PATCH /users/{id}/password`, `DELETE /users/{id}`, `POST /login` (throttle anti brute-force), `GET /me`, `POST /auth/refresh`, `POST /auth/logout`, CRUD `/reports` (signalements). Schéma Flyway (V1-V7), suite Testcontainers. |
| [`payment-service/`](payment-service/README.md) | Paiements manuels + Stripe (PaymentIntent/webhook) + PayPal (order/capture), rattachés à un `user_id` et (pour un abonnement) à un `travelId` | PostgreSQL `payment_db` | **Implémenté** : `POST /payments`, `GET /payments`, `GET /payments/{id}`, `PATCH /payments/{id}/status`, `DELETE /payments/{id}`, `DELETE /payments/by-user/{userId}` (token de service `service:identity`), endpoints Stripe (`PaymentIntent` + webhook) et PayPal (create order + capture, ownership-aware — ADR §10 addendum G2), `GET /payments/summary`, `GET /payments/income` (token de service `service:travel`, ADR §8bis). Schéma Flyway, suite Testcontainers. |
| [`travel-service/`](travel-service/README.md) | Graphe de destinations, transports (CRUD complet + itinéraires multi-destinations), abonnements, feedback, recherche Elasticsearch, recommandations, dashboards par rôle | Neo4j (Community Edition) + Elasticsearch (profil `search`) | **Implémenté** : CRUD `Destination` complet et *ownership-aware* (ADR §2, §10 addendum G1) ; `TRANSPORT` avec CRUD complet (`POST`/`PATCH`/`DELETE`) et `GET /destinations/{fromId}/routes/{toId}` (itinéraire multi-sauts, plus court chemin en nombre de sauts, bonus ADR §11) ; abonnements (`SubscriptionController`), feedback (`FeedbackController`), recommandations content-based (`RecommendationController`), recherche + autocomplete Elasticsearch, dashboards admin/manager/traveler (`DashboardController`, `ManagerStatsController`, `TravelerStatsController`). Suite Testcontainers (Neo4j). |
| [`admin-dashboard/`](admin-dashboard/README.md) | Front d'administration Angular | — | **Implémenté, conteneurisé** : front par rôle (Admin / Travel Manager / Voyageur) — connexion, inscription, catalogue + recherche Elasticsearch, abonnements, paiement (manuel/PayPal/carte via Stripe.js si une clé est configurée), feedback, stats/dashboards par rôle, itinéraires multi-destinations, mes voyages, abonnés, file de signalements, écrans admin `/users`, `/payments`, `/destinations` — câblés aux 3 APIs via la gateway, renouvellement de session silencieux (refresh token), PWA, scaffolding i18n (voir le README du front pour la table des routes et les limites réelles). `Dockerfile` (build Node + Nginx statique) et fragment Compose (`docker-compose.admin-dashboard.yml`, route `admin.localhost`, câblé dans `compose-assembly` comme payment/travel — opt-in, désactivé par défaut) comme les 3 backends, en plus de `ng serve`. |

## Socle commun aux 3 services Spring Boot

Spring Boot `3.4.3`, Java, build Maven (`./mvnw`), image construite depuis le
`Dockerfile` de chaque service.

- Variables d'environnement obligatoires, **fail-fast au démarrage** : aucun défaut
  silencieux pour un secret ou une URL de base.
- **Soft-delete** (`deleted_at` / `deletedAt`) : jamais de suppression physique, toute
  lecture filtre les lignes actives.
- `GlobalExceptionHandler` dédié par service.
- Healthcheck `/actuator/health`.
- Routage par **labels Docker** lus par Traefik : `identity.localhost`,
  `payment.localhost`, `travel.localhost`, `admin.localhost`, en HTTPS 443, TLS
  terminé au bord. Headers de sécurité HTTP (nosniff, frame-deny,
  referrer-policy, HSTS) sur les 4 routers — audit sécurité G7.
- Chaque service embarque son fragment Compose statique
  (`docker-compose.<service>.yml`), volontairement co-localisé avec le `Dockerfile` et
  les sources puisqu'il **builde depuis les sources**. Ces fragments sont inclus par
  chemin absolu par le rôle `compose-assembly` (voir
  [`ansible/README.md`](../ansible/README.md)).

## Non implémenté

Cette section décrivait l'état de la Phase 0 (pas de RBAC, pas de Stripe/PayPal, pas
d'entité `Travel`, pas de CRUD complet sur `TRANSPORT`) — tout cela est fait depuis les
phases 1 à 11 (voir `docs/lets-travel-architecture-decisions.md`). Ce qui reste
réellement ouvert :

- **Pas de « méthode de paiement » enregistrée** au sens strict du sujet : l'entité
  modélisée reste une transaction, pas un moyen de paiement réutilisable (carte
  enregistrée, etc.).
- **Stripe/PayPal non exercés contre un vrai compte** : le backend crée bien le
  PaymentIntent/l'order et confirme via webhook/capture, mais aucun test de ce projet
  n'a jamais parlé à Stripe ou PayPal en vrai (pas de compte de test). Le formulaire
  carte du front (Stripe.js) est câblé mais non testable pour la même raison.
- **Pathfinding en sauts, pas en durée pondérée** (`GET /destinations/{fromId}/routes/{toId}`) :
  le "plus court" itinéraire est le moins de correspondances, pas le plus rapide.
  Plafonné à 6 sauts.
- **Pas d'anti-doublon sur `TRANSPORT`**, et sa suppression est physique (pas de
  soft-delete, pas de trace d'audit d'un trajet retiré) — voir ADR §11 addendum.
- **Refresh token sans révocation en chaîne** : la rotation empêche le rejeu simple,
  mais un JWT d'accès déjà émis reste valide jusqu'à ses 15 minutes même après un
  `logout` ou un changement de mot de passe (mitigation partielle de G10, pas une
  fermeture complète — voir `docs/security-audit.md`).
- **Pas de reset de mot de passe par email** : un compte bloqué ne peut être débloqué
  que par un `ADMIN` (pas d'infra mail dans ce projet).
- **i18n en scaffolding seulement** : `@angular/localize` câblé, nav et login traduits
  en anglais, le reste du dashboard reste en dur en français (choix assumé, pas un
  oubli).
- **Pagination des avis/du classement manager : côté client seulement** (l'API ne
  pagine pas — la liste complète est toujours récupérée en un appel, le découpage en
  pages n'existe que dans l'affichage).
- **Conformité RGPD partielle, Vault en mode dev, pas de SAST/CVE en CI** et le reste
  des manques de sécurité classés M/B dans `docs/security-audit.md` (G9, G11, G13, G14)
  — dette assumée et documentée, pas un écart silencieux.

`sujet/lets-travel-sujet.md` et `sujet/lets-travel-audit.md`, cités par `CLAUDE.md`
comme référentiels de cette phase, **n'existent pas dans ce dépôt** (seul
`docs/sujet.md`, la Phase 0, est présent) — écart entre la documentation et le repo,
sans lien avec le code ci-dessus, à corriger séparément.
