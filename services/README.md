# services/

Sommaire des déployables. Un dossier = un déployable indépendant. Les frontières de
contexte sont détaillées dans [`docs/architecture-decisions.md`](../docs/architecture-decisions.md) §1.

Les détails de chaque service sont dans **son propre README** ; ce fichier ne donne
que le statut réel et le point d'entrée.

| Dossier | Rôle | Base | Statut réel |
|---|---|---|---|
| [`identity-service/`](identity-service/README.md) | Comptes + authentification : création de compte, login, JWT HS256, CRUD users, cascade de suppression vers `payment-service` | PostgreSQL `identity_db` | **Implémenté** : `POST /users`, `GET /users`, `GET /users/{id}`, `PATCH /users/{id}`, `DELETE /users/{id}`, `POST /login`, `GET /me`. Schéma Flyway, 7 classes de tests d'intégration Testcontainers. |
| [`payment-service/`](payment-service/README.md) | Transactions de paiement (montant, devise, statut), rattachées à un `user_id` | PostgreSQL `payment_db` | **Implémenté** : `POST /payments`, `GET /payments`, `GET /payments/{id}`, `PATCH /payments/{id}/status`, `DELETE /payments/{id}`, `DELETE /payments/by-user/{userId}` (accepté aussi pour le token de service `service:identity`). Schéma Flyway, 3 classes de tests d'intégration Testcontainers. |
| [`travel-service/`](travel-service/README.md) | Graphe de destinations (doublant l'entité « Travel » du sujet — ADR §2), abonnements, avis, recherche et recommandations | Neo4j (Community Edition) | **Implémenté** : CRUD `Destination` complet (dates, prix, capacité, activités, hébergements), abonnements avec paiement Stripe/PayPal/manuel, avis, recherche Elasticsearch + autocomplete, recommandations personnalisées. **Écart restant** : `TRANSPORT` sans mise à jour/suppression/anti-doublon, traversée limitée à 1 saut. |
| [`admin-dashboard/`](admin-dashboard/README.md) | Front d'administration Angular | — | **Implémenté, non conteneurisé** : front par rôle (Admin / Travel Manager / Voyageur) — connexion, inscription, catalogue + recherche Elasticsearch, abonnements, mes voyages, abonnés, file de signalements, et les écrans admin `/users`, `/payments`, `/destinations` — câblés aux 3 APIs via la gateway (voir le README du front pour la table des routes et ce qui manque : paiement, feedback, stats, PWA, i18n). Tourne via `ng serve` uniquement. |

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
  `payment.localhost`, `travel.localhost`, en HTTPS 443, TLS terminé au bord.
- Chaque service embarque son fragment Compose statique
  (`docker-compose.<service>.yml`), volontairement co-localisé avec le `Dockerfile` et
  les sources puisqu'il **builde depuis les sources**. Ces fragments sont inclus par
  chemin absolu par le rôle `compose-assembly` (voir
  [`ansible/README.md`](../ansible/README.md)).

## Non implémenté

- **Pas de CRUD complet sur `TRANSPORT`** : ni mise à jour, ni suppression, ni
  anti-doublon ; traversée limitée à 1 saut (pas de pathfinding multi-hop).
- **Aucune CI/CD pour cette phase** : `ci/jenkins/README.md` et
  `ci/sonarqube/README.md` restent des placeholders Phase 0 ; aucun pipeline
  n'exécute les tests ni n'analyse la qualité du code automatiquement.

~~Aucune intégration Stripe ni PayPal~~ — **corrigé** : `payment-service` intègre
Stripe (PaymentIntent + webhook) et PayPal (Order + capture) ; voir son README pour
le détail. Le formulaire de carte Stripe côté front est intégré (voir le README de
`admin-dashboard` pour la limite restante : pas de vraie clé publiable dans cet
environnement).

~~`admin-dashboard` non conteneurisé~~ — **corrigé** : `Dockerfile` (build Node
+ runtime `nginx-unprivileged` non-root) et fragment Compose statique
(`docker-compose.admin-dashboard.yml`), même mécanisme que les 3 services
Spring Boot (chemin absolu inclus par `compose-assembly`, cf. ligne 29-33
ci-dessus). Route Traefik `Host(\`localhost\`)` vérifiée bout-en-bout
(build, healthcheck, TLS, fallback SPA, cache des assets hashés).

~~Pas d'entité `Travel`~~ — **corrigé, par un choix assumé** : `Destination` porte
désormais dates, prix, capacité, activités et hébergements — c'est l'entité
« Travel » du sujet, sans nœud Neo4j séparé (voir
`docs/lets-travel-architecture-decisions.md` §2).

~~Pas de RBAC~~ — **corrigé** : trois rôles (`ADMIN` / `TRAVEL_MANAGER` /
`TRAVELER`) portés par chaque JWT et vérifiés côté service (`requireAdmin` /
`requireAnyRole` / vérifications *ownership-aware*), voir le README de chaque
service.
