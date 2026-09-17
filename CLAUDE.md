# Let's Travel — guide pour Claude Code

Ce repo enchaîne deux phases d'un même projet d'école (Zone01) :

- **`travel-plan` (Phase 0, terminée)** : infra (Ansible/Docker/Traefik/Vault),
  3 services Spring Boot (identity/payment/travel), dashboard Angular CRUD,
  un seul rôle `ADMIN` en dur. Décisions : `docs/architecture-decisions.md`.
  Sujet/audit d'origine : `docs/sujet.md` / `docs/audit_grille.md`.
- **`lets-travel` (Phase 1, en cours)** : ajoute 3 vrais rôles (Admin / Travel
  Manager / Traveler), recherche Elasticsearch, recommandations Neo4j,
  abonnements, feedback, signalements, dashboards par rôle. Sujet/audit :
  `sujet/lets-travel-sujet.md` / `sujet/lets-travel-audit.md` — **source de
  vérité pour toute feature de cette phase, à relire avant chaque décision**.
  Décisions : `docs/lets-travel-architecture-decisions.md`.

**Ne jamais éditer `sujet/*.md`** (référentiel de correction — un hook bloque
de toute façon toute écriture dessous). `docs/architecture-decisions.md` est
un document historique de la Phase 0 : ne pas le modifier rétroactivement, les
nouvelles décisions vont dans `docs/lets-travel-architecture-decisions.md`.

## Vue d'ensemble

```
services/identity-service/   PostgreSQL — comptes, auth JWT (HS256), rôles
services/payment-service/    PostgreSQL — paiements manuels + Stripe + PayPal
services/travel-service/     Neo4j       — destinations, transports (Travel à venir)
services/admin-dashboard/    Angular 22 — front, standalone/signals/Tailwind 4
ansible/roles/               Provisionnement (1 rôle = 1 responsabilité)
docs/                        Décisions d'architecture, sujets d'origine
sujet/                       Sujet + grille d'audit de la phase en cours (lecture seule)
```

Traefik est la gateway unique (TLS au bord, LB par labels Docker, ForwardAuth
délégué à identity-service). Détail stack/versions/réseaux/profils Compose :
`services/README.md` et `docs/architecture-decisions.md` §1-8.

## Conventions à respecter strictement

Ce sont des choix déjà faits, pas des suggestions — les casser à mi-projet
créerait une incohérence que la grille d'audit sanctionne ("code well
separated", "convince me").

- **Soft-delete partout** : `deletedAt`/`deleted_at`, jamais de suppression
  physique en première intention. Tout read filtre les lignes/nœuds actifs —
  côté Neo4j, filtrer `deletedAt IS NULL` sur **chaque hop** d'une traversée,
  pas seulement le nœud d'entrée.
- **Autorisation ad hoc, pas de filter chain** : chaque service a son propre
  `JwtService`/`TokenValidationService` (dupliqués volontairement — cohérent
  avec l'indépendance de services déjà actée, pas de lib Java partagée). Les
  contrôleurs appellent explicitement `authService.requireXxx(header)` /
  `tokenValidationService.requireValidToken(...)` en première ligne de
  méthode. Ne pas introduire `@PreAuthorize` ni de `SecurityFilterChain` :
  ce serait un deuxième mécanisme incohérent avec l'existant.
- **Env vars fail-fast** : aucune valeur par défaut silencieuse pour un secret
  ou une URL de base ; le service doit refuser de démarrer plutôt que de
  tourner avec un défaut dangereux.
- **Logging structuré** : JSON sur stdout + `requestId` en MDC propagé via
  `X-Request-Id` sur tout appel inter-service (voir `PaymentServiceClient`
  comme référence de pattern pour un futur appel travel↔payment).
- **Neo4j** : SDN repository pour les agrégats simples, `Neo4jClient` +
  Cypher explicite pour toute relation où le save-cascade de SDN
  réécrirait/effacerait des relations non chargées (voir `TransportRepository`,
  `ActivityRepository`).

## Tests

- **Intégration Testcontainers** en priorité pour tout endpoint backend (PostgreSQL
  ou Neo4j réel, jamais de mock de base) — c'est le pattern déjà utilisé par
  les 3 services.
- **Playwright e2e** (`services/admin-dashboard/e2e/`) pour tout parcours qui
  traverse plusieurs écrans (ex. login → action → vérification).
- **Vitest** pour la logique front unitaire (services, guards, interceptors).

## Process

- Une branche + une PR par feature/phase (le sujet §3 et l'audit l'exigent
  explicitement ; `services/README.md` liste déjà l'absence de ce workflow
  comme un écart de la Phase 0 — à corriger dès cette phase). Passer par le
  skill `/code-review` avant de merger.
- Toute nouvelle décision d'architecture non triviale suit le format de
  `docs/architecture-decisions.md` (Décision / Justification / Ce qui est
  sacrifié / Alternative rejetée) dans `docs/lets-travel-architecture-decisions.md`
  — utiliser `/adr <sujet>`.
- Garder les sections "Non implémenté / écarts avec le sujet" des READMEs
  honnêtes et à jour au fil de l'implémentation — utiliser `/audit-sync`.

## Commandes disponibles

- `/adr <sujet>` — rédige une entrée de décision avant de coder une phase.
- `/new-endpoint <service> <ressource>` — scaffold un endpoint au pattern exact du service ciblé.
- `/new-migration <service> <nom>` — prochaine migration Flyway, ou contrainte Neo4j pour travel-service.
- `/audit-sync` — recale les READMEs sur l'état réel vs `sujet/lets-travel-audit.md`.
