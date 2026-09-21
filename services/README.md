# services/

Sommaire des déployables. Un dossier = un déployable indépendant. Les frontières de
contexte sont détaillées dans [`docs/architecture-decisions.md`](../docs/architecture-decisions.md) §1.

Les détails de chaque service sont dans **son propre README** ; ce fichier ne donne
que le statut réel et le point d'entrée.

| Dossier | Rôle | Base | Statut réel |
|---|---|---|---|
| [`identity-service/`](identity-service/README.md) | Comptes + authentification : création de compte, login, JWT HS256, CRUD users, cascade de suppression vers `payment-service` | PostgreSQL `identity_db` | **Implémenté** : `POST /users`, `GET /users`, `GET /users/{id}`, `PATCH /users/{id}`, `DELETE /users/{id}`, `POST /login`, `GET /me`. Schéma Flyway, 7 classes de tests d'intégration Testcontainers. |
| [`payment-service/`](payment-service/README.md) | Transactions de paiement (montant, devise, statut), rattachées à un `user_id` | PostgreSQL `payment_db` | **Implémenté** : `POST /payments`, `GET /payments`, `GET /payments/{id}`, `PATCH /payments/{id}/status`, `DELETE /payments/{id}`, `DELETE /payments/by-user/{userId}` (accepté aussi pour le token de service `service:identity`). Schéma Flyway, 3 classes de tests d'intégration Testcontainers. |
| [`travel-service/`](travel-service/README.md) | Graphe de destinations et relations `TRANSPORT` dirigées | Neo4j (Community Edition) | **Partiel** : CRUD `Destination` complet (`POST`, `GET` liste et unitaire, `PUT`, `DELETE`) + `POST`/`GET /destinations/{id}/transports` (traversée 1-hop). **Aucune entité `Travel`**, ni activité, ni hébergement, ni dates. 3 classes de tests d'intégration Testcontainers. |
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

- **Aucune intégration Stripe ni PayPal** : aucun SDK, aucun appel HTTP sortant vers
  un fournisseur de paiement. Tous les paiements sont créés manuellement ; la colonne
  `external_reference` existe mais reste inutilisée.
- **Pas de « méthode de paiement »** au sens du sujet : l'entité modélisée est une
  transaction, pas un moyen de paiement enregistré.
- **Pas d'entité `Travel`** (dates, durée, activités, hébergement) côté
  `travel-service` : uniquement `Destination` et la relation `TRANSPORT`.
- **Pas de CRUD complet sur `TRANSPORT`** : ni mise à jour, ni suppression, ni
  anti-doublon ; traversée limitée à 1 saut.
- **Pas de RBAC** : le JWT ne porte aucune claim de rôle, aucun profil « Admin »
  n'existe ; toute route protégée est ouverte à n'importe quel token valide, et aucune
  route n'est *ownership-aware*.
- **`admin-dashboard` non conteneurisé** : pas de `Dockerfile`, pas de fragment
  Compose, pas de route Traefik.
