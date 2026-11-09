# Travel-Plan

Environnement microservices + Admin Dashboard pour un système de gestion de voyages.

**Statut : Phase 1 (« Let's Travel ») implémentée et mergée dans `main`.** Trois
rôles (Admin / Travel Manager / Traveler), recherche Elasticsearch, recommandations
Neo4j, abonnements avec paiement, avis, signalements, dashboards par rôle, plus les
bonus PWA, i18n FR/EN et une feature innovante. Décisions : `docs/architecture-decisions.md`
(Phase 0, historique) et `docs/lets-travel-architecture-decisions.md` (Phase 1).

Ce README décrit **ce qui existe réellement dans le dépôt**. Ce qui est prévu par le
sujet mais absent ou partiel est regroupé — et uniquement regroupé — dans la section
[Non implémenté / écarts avec le sujet](#non-implémenté--écarts-avec-le-sujet).

## Arborescence

```
services/        3 services Spring Boot + le front Angular
ansible/roles/   11 rôles de provisionnement (1 rôle = 1 responsabilité)
ci/              Jenkins / SonarQube — README uniquement ; la config réelle est
                 rendue par les rôles Ansible `jenkins` et `sonarqube`
k8s/             Manifestes Kubernetes de démonstration (bonus, voir son README)
sujet/           Sujet et grille d'audit de la Phase 1 (lecture seule)
docs/            Sujet, grille d'audit, décisions d'architecture
```

## Stack et versions effectivement utilisées

| Brique | Version épinglée | Où |
|---|---|---|
| PostgreSQL | `postgres:17.5-bookworm` | rôle `postgres` |
| Neo4j | `neo4j:5.26.6-community` | rôle `neo4j` |
| Traefik | `traefik:v3.7.1` | rôle `traefik` |
| Vault (dev mode) | `hashicorp/vault:1.18.3` | rôle `vault` |
| Spring Boot | `3.4.3` | les 3 services |
| Angular | `22.x` (Tailwind 4, Vitest) | `admin-dashboard` |

Aucune image en `latest`. Orchestration : Docker Compose uniquement.

---

## Infrastructure (Ansible)

Modèle opératoire : **Ansible provisionne et rend des fragments Compose**,
**docker-compose orchestre**. Les rôles ne lancent pas la stack eux-mêmes.

### Rôles existants

| Rôle | Ce qu'il fait réellement |
|---|---|
| `docker-host` | Prépare l'hôte Ubuntu : pré-check non destructif des paquets distro conflictuels (échoue au lieu de supprimer), clé GPG + dépôt APT Docker en deb822, installe `docker-ce`/`cli`/`containerd.io`/`buildx`/`compose-plugin`, active le démon, ajoute les utilisateurs au groupe `docker`. |
| `postgres` | Rend `compose.postgres.yml` (image épinglée, `data-net`, `mem_limit: 256m`, healthcheck, **aucun port publié**). Crée 2 bases (`identity_db`, `payment_db`) et 2 comptes `NOSUPERUSER/NOCREATEDB/NOCREATEROLE` propriétaires chacun de leur seule base. Révoque `CONNECT` à `PUBLIC` puis l'accorde au seul propriétaire → cloisonnement croisé effectif. Peut lire depuis Vault les mots de passe des comptes applicatifs qu'il provisionne (`postgres_vault_read`, `false` par défaut) — lecture cantonnée à son bloc `provision`, le rôle **ne touche pas** au `.env`. Ne crée **aucune** table (Flyway côté services). |
| `app-secrets` | Propriétaire **unique** de `/opt/travel-plan/.env`, le fichier d'environnement runtime auto-chargé par Compose. Lit les secrets dans Vault (KV v2, mount `secret` : `infra/postgres-superuser`, les `*/db` des services, `identity/bootstrap-admin` → `BOOTSTRAP_ADMIN_EMAIL`/`BOOTSTRAP_ADMIN_PASSWORD`, `shared/jwt`, et les deux secrets d'outillage `sonarqube/db` → `SONARQUBE_DB_PASSWORD` et `observability/grafana` → `GRAFANA_ADMIN_PASSWORD`) et les matérialise dans un `.env` rendu intégralement par un seul `template` — un fichier, un propriétaire, sinon deux rôles s'écraseraient l'un l'autre à chaque run. Piloté par `app_secrets_vault_read` (`false` par défaut) : à `false`, no-op intégral (aucune lecture Vault, aucun fichier écrit), ce qui le rend Molecule-safe comme `postgres_vault_read`. |
| `neo4j` | Rend `compose.neo4j.yml` : image épinglée, `data-net`, `mem_limit: 1g`, volume nommé, healthcheck, aucun port publié. |
| `elasticsearch` | Rend `compose.elasticsearch.yml` : Elasticsearch 8.15.3 en mode dev (sécurité xpack désactivée, donc jamais joignable hors `data-net`), image épinglée, aucun port publié, profil Compose `search`. Ne crée ni index ni mapping : c'est `travel-service` qui s'en charge. |
| `vault` | Rend `compose.vault.yml` (Vault **dev mode**, `data-net`, `mem_limit: 96m`, root token via variable d'env, aucun port publié). Vérifie le moteur KV v2 sur `secret/`, écrit 10 chemins de secrets runtime (`infra/postgres-superuser`, `identity/db`, `identity/bootstrap-admin` (premier ADMIN), `payment/db`, `payment/stripe`, `payment/paypal`, `travel/db`, `shared/jwt`, plus les deux secrets d'outillage `sonarqube/db` et `observability/grafana`) et pose 3 policies cloisonnées (`identity-policy`, `payment-policy`, `travel-policy`), chacune limitée à son propre préfixe. |
| `traefik` | La gateway : **seul rôle publiant un port** (443) et seul à chevaucher `edge-net` + `backend-net`. Rend la config statique (entrypoint `websecure`, provider Docker avec `exposedByDefault: false`, provider file, endpoint `ping`), la config dynamique TLS, et génère un certificat auto-signé (mkcert si présent, sinon openssl ; idempotent via `creates:`). Socket Docker monté en lecture seule (`:ro`). Dashboard Traefik désactivé par défaut. |
| `compose-assembly` | Crée les 3 réseaux bridge via `community.docker.docker_network` (Ansible en est propriétaire, les fragments les déclarent `external: true`) et rend le `docker-compose.yml` top-level qui assemble **tous** les fragments du projet via `include:` — y compris `jenkins`, `sonarqube` et `observability`, chacun derrière son flag — et applique les profils. Calcule la liste de profils de `traefik` : tout profil contenant un service routé doit embarquer la gateway, sinon l'UI démarre injoignable. |
| `jenkins` | Le contrôleur CI (profil Compose `ci`) : rend `compose.jenkins.yml` (image épinglée, volume nommé pour `JENKINS_HOME`, `backend-net`, aucun port publié, route Traefik par labels) et provisionne 3 jobs Pipeline déclenchés à la main, un par service. |
| `sonarqube` | Le serveur d'analyse qualité (profil Compose `ci`, le même que `jenkins`) : rend `compose.sonarqube.yml` déclarant SonarQube Community **et sa propre base Postgres dédiée** (conteneur, volume et réseau distincts de la Postgres applicative, sur un réseau `internal`), les **trois** heaps JVM bornées explicitement (Web + Compute Engine + Elasticsearch embarqué vivent dans la même cgroup), aucun port publié, route Traefik sur `sonarqube.localhost`. Pose aussi sur l'**hôte** les sysctl exigés par l'Elasticsearch embarqué (`vm.max_map_count`, `fs.file-max`) : non namespacés, donc impossibles à poser depuis Compose. Ne crée **aucun** token et ne configure rien via l'API authentifiée (procédure manuelle documentée). |
| `observability` | Le logging centralisé (profil Compose dédié `observability`) : rend les configs **Loki** (monolithique, stockage filesystem, schéma TSDB v13, rétention 7 j avec compactor actif), **Promtail** (découverte par l'API Docker, socket en lecture seule, liste blanche sur les conteneurs applicatifs, extraction du JSON émis par les services) et la **datasource Grafana** pré-provisionnée, plus le fragment Compose des 3 conteneurs. Loki et Promtail vivent sur un réseau `internal`, **sans port ni route** : l'API de Loki n'a aucune authentification, le seul accès humain aux logs est Grafana (`grafana.localhost`). |

### Réseaux

Trois réseaux bridge, créés et possédés par Ansible (`docker compose down` ne les
supprime pas) :

- `edge-net` — exposition publique, Traefik seul dessus côté extérieur
- `backend-net` — Traefik ↔ services applicatifs
- `data-net` — services ↔ PostgreSQL / Neo4j / Vault

Aucun conteneur hors Traefik ne publie de port vers l'hôte.

### Profils Compose

| Profil | Contenu |
|---|---|
| `core` | `postgres`, `vault`, `traefik` |
| `full` | `core` + `neo4j` + les services applicatifs activés |
| `ci` | `jenkins` + `sonarqube` + sa base (inclus seulement si les flags `assembly_*_enabled` correspondants sont à `true`, `false` par défaut) |
| `search` | `elasticsearch` — **additif** : `--profile full --profile search`. `travel-service` reste utilisable sans lui (recherche et autocomplete répondent 503). |
| `observability` | `loki` + `promtail` + `grafana` — **additif** aux autres : `--profile full --profile observability`. ~450 Mo mesurés. |

Neo4j (poste RAM le plus lourd) est exclu de `core`. Les trois services applicatifs
sont en profil `full` uniquement.

Leurs fragments Compose sont **statiques et vivent dans le dépôt** (à côté du
Dockerfile, puisqu'ils buildent depuis les sources) et sont inclus par **chemin
absolu**, via des flags dans `compose-assembly/defaults/main.yml` :

| Flag | Défaut | Signification |
|---|---|---|
| `assembly_identity_enabled` | `true` | Raccordement identity ↔ Traefik prouvé bout-en-bout |
| `assembly_payment_enabled` | `false` | Intégration non rejouée par défaut, à activer explicitement |
| `assembly_travel_enabled` | `false` | Idem |

Les chemins (`assembly_*_fragment`) sont vides par défaut et doivent être fournis en
`-e` : ils varient d'une machine à l'autre.

Les fragments d'**outillage**, eux, sont rendus par un rôle Ansible sous
`/opt/travel-plan/` : chemin **relatif**, rien à fournir en `-e`. Ils restent
néanmoins derrière un flag, parce qu'un `include:` Compose est résolu **quel que
soit** le `--profile` — inclure en dur un fragment absent du disque casserait aussi
le rendu de `core` et de `full` :

| Flag | Défaut | Fragment inclus |
|---|---|---|
| `assembly_jenkins_enabled` | `false` | `jenkins/compose.jenkins.yml` |
| `assembly_sonarqube_enabled` | `false` | `sonarqube/compose.sonarqube.yml` |
| `assembly_observability_enabled` | `false` | `observability/compose.observability.yml` |

Une fois ces flags passés à `true` lors du rendu, **plus aucun `-f` manuel** n'est
nécessaire : `docker compose --profile ci up -d` et
`docker compose --profile full --profile observability up -d` suffisent.

### Réplicas & load balancing

Les trois services applicatifs (`identity-service`, `payment-service`,
`travel-service`) tournent à **2 réplicas par défaut** (sujet §1 : *« multiple
replicas of each microservice for load balancing and failover mechanisms »*).

**Prérequis : aucun `container_name` sur ces trois services.** Un `container_name`
est unique par daemon Docker : il rend structurellement impossible de démarrer une
2ᵉ instance, et Compose rejette explicitement `container_name` + `replicas > 1`.
Les noms sont donc générés par Compose (`travel-plan-identity-service-1`, `-2`, …).
Corollaire : **ne jamais cibler ces conteneurs par un nom en dur** dans un script —
on adresse par **nom de service** (`docker compose logs identity-service`), jamais
par nom de conteneur. Les conteneurs d'infra (`postgres`, `neo4j`, `vault`,
`traefik`, `jenkins`) sont des singletons et gardent leur `container_name`.

**Déclenchement.** `deploy.replicas` est honoré par un simple `docker compose up`
**hors Swarm** avec le Compose V2 de ce projet (vérifié sur `Docker Compose version
v5.5.1` : `docker compose up -d identity-service` crée bien 2 conteneurs). Sur une
version de Compose plus ancienne qui ignorerait `deploy.replicas`, l'équivalent
explicite est `docker compose up -d --scale identity-service=2 identity-service`.

**Réglage RAM.** Le nombre de réplicas est interpolé, pas figé : sur un poste
contraint, redescendre sans éditer les fragments via
`IDENTITY_REPLICAS` / `PAYMENT_REPLICAS` / `TRAVEL_REPLICAS` (ex. `TRAVEL_REPLICAS=1`,
`travel-service` étant adossé à Neo4j, le poste le plus lourd). `--scale` reste
prioritaire sur ces valeurs.

```bash
# scale à la volée
docker compose --profile full up -d --scale identity-service=3 identity-service
# retour à 1 réplica
docker compose --profile full up -d --scale identity-service=1 identity-service
```

**Load balancing.** Rien à configurer côté Traefik : le provider Docker agrège
**tous** les conteneurs portant les mêmes labels
`traefik.http.services.<nom>.loadbalancer.*` dans un seul pool de serveurs, et
répartit en round-robin. Les nouvelles réplicas entrent dans le pool sans
reconfiguration ni redémarrage de la gateway.

**Appels inter-services.** `PAYMENT_SERVICE_URL` vaut `http://payment-service:8080`,
soit le **nom de service** Compose : le DNS interne Docker le résout vers les IP de
toutes les réplicas et alterne. Les appels internes sont donc load-balancés eux
aussi, sans passer par Traefik.

**Vérifié empiriquement** (2 réplicas `identity-service`, stack `full`) :
- 40 requêtes via Traefik sur `https://identity.localhost/actuator/health` →
  trafic réparti quasi à parité entre les deux instances (11 774 vs 11 914 octets
  reçus) : round-robin effectif ;
- instance n°2 arrêtée → **20/20** requêtes toujours en `200`, aucune erreur :
  Traefik sort l'instance morte du pool (failover).

### Tests d'infrastructure

| Rôle | Test |
|---|---|
| `postgres`, `neo4j`, `vault`, `traefik`, `app-secrets`, `jenkins`, `sonarqube`, `observability` | Scénario **Molecule** (driver docker, conteneurs frères) avec étape `idempotence` (2ᵉ converge = `changed=0`) et `verify` assertant l'état réel. Pour `app-secrets`, le scénario couvre le no-op avec `app_secrets_vault_read=false` et l'idempotence, **pas** le chemin Vault (qui exigerait un Vault provisionné et des secrets réels) |
| `elasticsearch` | Pas de Molecule (un conteneur dev-mode qui ne fait que rendre un fragment Compose, même geste que `neo4j` ; justifié dans `docs/lets-travel-architecture-decisions.md` §6) |
| `docker-host` | Pas de Molecule (testerait l'install Docker → exigerait DinD, écarté et documenté) ; playbook `test-local.yml` + 2ᵉ run manuel |
| `compose-assembly` | Pas de Molecule ; playbook `test-local.yml` |

```bash
source .venv-ansible/bin/activate
cd ansible/roles/<rôle> && molecule test
```

---

## Services

Les trois services partagent le même socle : variables d'environnement obligatoires
(fail-fast au démarrage, aucun défaut silencieux pour un secret), soft-delete
(`deleted_at`/`deletedAt`, jamais de suppression physique ; toute lecture filtre les
lignes actives), `GlobalExceptionHandler` dédié, healthcheck `/actuator/health`,
routage Traefik par labels Docker (`identity.localhost`, `payment.localhost`,
`travel.localhost`, HTTPS 443, TLS terminé au bord), logs JSON avec `requestId`.
L'autorisation est **ad hoc** : chaque contrôleur appelle explicitement
`requireAdmin` / `requireAnyRole` / une vérification d'ownership en première ligne,
sans `SecurityFilterChain` ni `@PreAuthorize`. Le détail des endpoints vit dans le
README de chaque service ; ci-dessous, ce que chacun porte.

### identity-service — PostgreSQL (`identity_db` / `identity_user`)

Comptes, authentification et rôles. Schéma Flyway, Hibernate en `ddl-auto: validate`.
Mot de passe haché BCrypt ; **JWT HS256** (15 min) portant le rôle (`ADMIN`,
`TRAVEL_MANAGER`, `TRAVELER`), plus refresh token ; limitation des tentatives de
login ; création d'`ADMIN` par bootstrap Vault ou par un admin existant (jamais en
auto-inscription) ; signalements (`reports`) entre utilisateurs ; cascade de
suppression vers payment-service via un token de service dédié (best-effort, hors
transaction).

### payment-service — PostgreSQL (`payment_db` / `payment_user`)

Paiements `MANUAL`, **Stripe** (PaymentIntent + webhook) et **PayPal** (Order +
capture), liés à un abonnement de `travel-service` : le résultat du paiement est
renvoyé au service voyage, avec réconciliation (`POST /payments/reconcile-subscriptions`).
Statuts terminaux immuables, ownership `user_id`, token de service scopé à ses
seules routes internes.

### travel-service — Neo4j (Community Edition) + Elasticsearch optionnel

Le graphe porte `Destination` (c'est l'entité « Travel » du sujet : dates, prix,
capacité, activités, hébergements), `TRANSPORT` (CRUD + pathfinding multi-hop),
les abonnements (`SUBSCRIBED`, cutoff 3 jours), les avis (`GAVE_FEEDBACK`),
les statistiques et le classement des organisateurs, les dashboards Admin / Manager /
Traveler, les **recommandations** personnalisées (score pondéré sur plusieurs champs
du voyage), la **recherche et l'autocomplete Elasticsearch**, et la feature bonus
(Travel Buddies, itinéraires combinés, badges voyageur). Création de relations par
**Cypher explicite via `Neo4jClient`**, pas `repository.save()` (SDN réécrirait les
relations non chargées).
**Dette documentée** : Neo4j Community Edition n'a ni RBAC ni multi-tenant — le
service se connecte avec l'unique compte administrateur `neo4j`.

---

## admin-dashboard (Angular 22)

Application standalone, routes lazy-loaded, Tailwind 4, signals, zoneless. Navigation
par rôle (le menu et les gardes de route changent selon le JWT) :

| Rôle | Écrans principaux |
|---|---|
| Traveler | Catalogue avec recherche/autocomplete, fiche voyage (inscription, paiement, avis, signalement), « Mes abonnements », « Mes statistiques », recommandations, itinéraires suggérés, badges, page publique d'un organisateur |
| Travel Manager | « Mes voyages » (CRUD), abonnés d'un voyage, avis d'un voyage, dashboard organisateur (revenus, voyageurs, note) |
| Admin | Dashboard (top managers/voyages, revenus, historique), classement, avis, signalements, utilisateurs, paiements, destinations/transports |

- **Layout partagé** (`AppShellComponent`) : navigation, bascule de thème,
  sélecteur FR/EN, déconnexion, lien d'évitement vers le contenu.
- **Thème clair/sombre**, **PWA installable**, **i18n FR/EN** sur tous les écrans,
  accessibilité auditée (axe-core), refresh token côté intercepteur.
- Composants réutilisables sous `shared/ui/`.
- Les URLs backend pointent vers la gateway (`https://identity.localhost`, …).
- Conteneurisé (Dockerfile + nginx non-root + route Traefik). Détail et limites :
  `services/admin-dashboard/README.md`.

---

## Tests existants

| Périmètre | Nature |
|---|---|
| `identity-service` | 18 classes / 109 `@Test` — intégration Spring Boot + Testcontainers PostgreSQL |
| `payment-service` | 19 classes / 103 `@Test` — intégration Testcontainers + tests unitaires Mockito (Stripe/PayPal) |
| `travel-service` | 20 classes / 212 `@Test` — intégration Testcontainers Neo4j (+ Elasticsearch réel pour la recherche) |
| `admin-dashboard` unitaires | 55 fichiers / 411 tests (Vitest via `ng test`) |
| `admin-dashboard` e2e | 14 specs Playwright / 36 tests (parcours multi-écrans, rôles, accessibilité) |
| Ansible | Molecule sur 8 rôles sur 11 (cf. tableau plus haut) |

---

## Non implémenté / écarts avec le sujet

Cette section liste ce que le sujet ou [`docs/audit_grille.md`](docs/audit_grille.md)
attendent et qui est **absent ou partiel** dans le dépôt aujourd'hui.

### CI/CD
- **Jenkins : partiel, déclenchement automatique.** Le rôle Ansible `jenkins`
  provisionne un contrôleur (profil Compose `ci`) et 3 jobs Pipeline, un par
  service, jouant `./mvnw test` puis l'analyse SonarQube. Déclenchement par
  **poll SCM** sur `main` (pas de webhook : Jenkins est local, injoignable depuis
  GitHub) — vérifié de bout en bout. Les `Jenkinsfile` des 3 services ont
  aussi des stages **Build image** et **Deploy** (rollback auto vers l'image
  précédente si le healthcheck échoue), **opt-in** via
  `jenkins_deploy_enabled` (ADR §14) : **vérifiés à la main sur `identity-service`**
  (build puis recreate des replicas jusqu'à `healthy`, depuis le conteneur
  Jenkins), pas via un build Jenkins complet ni pour payment/travel : le
  `docker-compose.yml` rendu actuellement n'inclut que identity et
  admin-dashboard (flags `assembly_*`). Pas de registry, pas de job
  `admin-dashboard`.
  `ci/jenkins/README.md` et `ci/sonarqube/README.md` sont désormais alignés mais
  restent des notes d'orientation, pas la config (rendue par les rôles Ansible).
- ~~**SonarQube : absent.**~~ **Corrigé — SonarQube tourne et analyse réellement.**
  Le rôle Ansible `sonarqube` provisionne un serveur Community Edition + sa base
  Postgres **dédiée** (profil Compose `ci`, route Traefik `sonarqube.localhost`,
  aucun port publié). Les 3 `pom.xml` embarquent `sonar-maven-plugin` (version
  **épinglée**), et les 3 `Jenkinsfile` ont deux stages : analyse puis **relecture
  du quality gate via l'API REST** — indispensable, le scanner rend la main
  *avant* que le gate soit calculé, donc un `sonar:sonar` vert ne dit rien à lui
  seul.
  **Analyse réelle exécutée sur `identity-service`** : tâche Compute Engine
  `SUCCESS`, **quality gate `OK`**, 825 ncloc, **0 bug, 0 vulnérabilité,
  7 code smells** (4 MAJOR, 3 INFO), 0 % de duplication.
  *Mise à jour* : JaCoCo est maintenant branché dans les 3 `pom.xml` (la couverture
  est remontée à Sonar), et les code smells qui bloquaient le quality gate ont été
  corrigés en deux passes (commits `e5009a2`, `f2af9d4`). Le « OK » de la toute
  première analyse était obtenu par absence de condition (pas de période de
  référence) ; l'état du gate sur la dernière analyse n'a pas été rejoué pour cette
  mise à jour de documentation.
- **Le token Sonar n'est ni dans le dépôt ni provisionné par Ansible.** Le mot de
  passe de la base SonarQube, lui, passe désormais par Vault → `app-secrets` →
  `.env` comme tous les autres secrets ; le **token d'analyse** est d'une autre
  nature : il n'existe qu'après le premier démarrage du serveur et se crée via son
  API authentifiée. Le contrat du projet interdit autant le credential en clair que
  le placeholder « temporaire ». Procédure manuelle
  documentée (génération API + dépôt dans le credential store de Jenkins sous
  l'ID `sonarqube-token`). Les stages Sonar sont **conditionnels** : sans la
  variable `SONAR_HOST_URL`, les pipelines tournent sans analyse au lieu d'échouer.
- Build d'image + déploiement : vérifiés à la main sur identity-service seulement
  (voir ci-dessus) ; pas de registry, pas de job `admin-dashboard`. Les tests
  sont déclenchés par le poll SCM sur `main`.

### Sécurité / secrets
- **Vault non consommé au runtime par les services** : les services ne parlent jamais
  à Vault et n'ont aucun mécanisme d'authentification côté service (ni AppRole, ni
  Vault Agent). C'est Ansible, avec son token, qui lit les secrets : le rôle
  `app-secrets` les matérialise dans `/opt/travel-plan/.env` (uniquement si
  `app_secrets_vault_read=true`, défaut `false`), et le rôle `postgres` en relit
  certains pour provisionner ses comptes (`postgres_vault_read`, qui dérive
  désormais par défaut de `app_secrets_vault_read`).
  **Règle opérationnelle** : comme il n'existe pas de `site.yml` et que chaque rôle
  est joué par une invocation `ansible-playbook` séparée (aucun état partagé), un
  run réel doit passer `-e app_secrets_vault_read=true` sur **les deux**
  invocations — y compris celle du rôle `postgres`, avec ce **même** nom de flag.
  Activer l'un sans l'autre fait démarrer le conteneur Postgres avec le vrai secret
  Vault pendant que le provisioning tente le placeholder `changeme_postgres_superuser`
  → échec d'authentification immédiat, et mismatch runtime silencieux des comptes
  `identity_user` / `payment_user` côté identity/payment-service.
  Avec les deux flags à `false`, aucun `.env` n'est rendu : les fragments Compose
  utilisent `${VAR:?...}` sans fallback et la stack refuse alors de démarrer plutôt
  que de tourner sur un secret par défaut.
- **Secrets de développement versionnés dans les `defaults/`** : les valeurs écrites
  dans Vault par le rôle `vault` (dont `shared/jwt`, la clé de signature partagée par
  les trois services) sont des placeholders de dev lisibles dans le dépôt. Aucun
  credential réel n'est versionné, mais toute valeur réelle doit être fournie en
  surcharge (`-e` ou fichier chiffré `ansible-vault`).
- ~~**RBAC absent partout**~~ **Corrigé** : le JWT porte le rôle (`ADMIN` /
  `TRAVEL_MANAGER` / `TRAVELER`), chaque contrôleur le vérifie explicitement et les
  routes sensibles sont *ownership-aware* (un manager ne touche que ses voyages).
  Un `ADMIN` ne peut pas être créé par auto-inscription. Limite : l'autorisation est
  appliquée service par service, sans filtre transverse (choix assumé, ADR §1).
- **TLS partiel** : Traefik termine bien le TLS en 443 avec certificat auto-signé
  (mkcert/openssl) et aucun entrypoint HTTP en clair, mais les communications
  internes (Traefik → services, identity → payment) sont en HTTP simple sur
  `backend-net`, choix assumé.
- Le socket Docker est monté dans Traefik (lecture seule) : risque quasi-root accepté
  et documenté, pas de docker-socket-proxy.

### Fonctionnel
- **Stripe et PayPal : intégrés mais jamais exercés contre les vrais fournisseurs.**
  Le code (PaymentIntent + webhook, Order + capture) et le formulaire Stripe.js du
  front existent et sont testés avec des doubles ; Vault contient des identifiants
  factices et le front n'a pas de clé publiable réelle, donc `POST /payments/stripe`
  répond 502 dans cet environnement. Le paiement `MANUAL` et le flux d'abonnement
  sont, eux, bout-en-bout.
- ~~**Un paiement `FAILED` est compté comme une annulation**~~ **Corrigé** : l'abonnement
  reste `CANCELLED` côté API, mais porte un `cancelReason = PAYMENT_FAILED` que le
  compteur d'annulations du voyageur ignore. Toujours **pas de remboursement**.
- ~~**Avis immuables et non modérables**~~ **Corrigé** : l'auteur modifie
  (`PUT`) ou retire (`DELETE`) son avis, l'`ADMIN` modère (`DELETE /feedback/{id}`),
  le tout en soft-delete ; un avis retiré n'entre plus dans aucune moyenne, badge
  ni recommandation. Un avis modéré ne peut pas être reposté par son auteur.
- **Statistiques voyageur** (`GET /travelers/me/stats`) : participations passées,
  annulations, avis donnés, moyen de paiement préféré. Les signalements ne sont
  comptés que **côté reçu** (le sujet ne demande que « report counts »), pas
  côté émetteur. Un organisateur ne voit d'un abonné que son identifiant, pas un
  profil.
- ~~**Pas d'anti-doublon à la création d'un `TRANSPORT`**~~ **Corrigé** : un trajet
  actif identique (origine, destination, mode) répond 409. Le pathfinding reste
  « fewest-hops », pas le plus court en durée (un plus-court-chemin pondéré
  demanderait APOC, non installé).
- **Recherche Elasticsearch** : dual-write synchrone depuis `DestinationService`,
  échec d'indexation loggé et avalé. ~~Pas de ré-indexation~~ **Corrigé** :
  `POST /destinations/reindex` (`ADMIN`) reconstruit l'index depuis Neo4j et renvoie
  `{indexed, failed}`. Cohérence PostgreSQL / Neo4j / Elasticsearch : applicative et
  *best-effort*, pas de transaction distribuée.
- **Recommandations** : score pondéré à la main sur plusieurs champs du voyage, pas
  de modèle appris ni de filtrage collaboratif.
- **PWA : coque applicative seulement.** Installable, assets en cache, mais **pas de
  mode hors-ligne pour les données, pas de synchronisation en arrière-plan ni de
  notifications push** (le service worker ne met volontairement aucune réponse API en
  cache). L'enregistrement réel du service worker n'a pas pu être vérifié dans le
  navigateur de test intégré.
- **i18n FR/EN : tous les écrans, mais pas tout le contenu.** Les formats de
  nombres/dates/devises restent en `fr-FR`, les messages d'erreur de l'API et les
  raisons des recommandations ne sont pas traduits, et il n'y a pas de notifications
  ni de contenu utilisateur traduit.
- ~~Pas d'entité `Travel`~~, ~~pas de CRUD complet sur `TRANSPORT`~~, ~~pas de
  modification de destination ni de bouton de déconnexion dans le dashboard~~ :
  **corrigés** (`Destination` porte dates/prix/capacité/activités/hébergements ;
  CRUD + pathfinding `TRANSPORT` ; écran d'édition ; déconnexion dans le layout).
- **Pas de « méthodes de paiement » enregistrées** : l'entité est une transaction
  (`amount`, `currency`, `status`, fournisseur), pas un moyen de paiement mémorisé.

### Infrastructure / exploitation
- **Aucun playbook d'orchestration global ni inventaire.** `ansible/` ne contient que
  `roles/` et un README : pas de `site.yml`, pas de `inventory`. Chaque rôle
  s'applique via son propre `test-local.yml` ou son scénario Molecule, et l'ordre
  d'application (rôles de service puis `compose-assembly`) est documenté en commentaire,
  pas automatisé.
- ~~Pas de réplicas, pas de load balancing effectif, pas de failover.~~ **Corrigé** —
  les trois services applicatifs tournent à **2 réplicas par défaut**, Traefik
  équilibre le trafic entre elles et retire une instance morte du pool. Voir
  [Réplicas & load balancing](#réplicas--load-balancing) pour le détail et la preuve.
- ~~**Pas de logging traçable inter-services.**~~ **Corrigé — la traçabilité
  inter-services fonctionne et a été vérifiée.** Les 3 services émettent des logs
  **JSON** sur stdout (`logstash-logback-encoder`) portant un `requestId` en MDC
  (`RequestIdFilter`, en-tête `X-Request-Id`), propagé sur l'appel en cascade
  identity → payment. Le rôle Ansible **`observability`** provisionne
  **Promtail → Loki → Grafana** (profil Compose dédié `observability`).
  **Preuve réelle** : un `DELETE /users/{id}` déclenchant la cascade, émis avec un
  `X-Request-Id` connu, puis la requête LogQL
  `{job="travel-plan"} | json | requestId=\`…\`` retourne **10 lignes réparties
  sur les deux services**, reconstituant la cascade dans l'ordre
  (`DELETE /users/…` → `DELETE /payments/by-user/…` → `Completed 200 OK` →
  `Completed 204 NO_CONTENT`).
  **Deux limites nommées** : (1) à niveau `INFO` une requête nominale n'écrit
  **aucune** ligne — un MDC sans instruction de log ne produit rien, la
  corrélation n'a été observable qu'avec
  `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB=DEBUG` ; il manque donc des **logs
  métier** côté applicatif. (2) Toute image de service construite **avant**
  l'ajout de `logback-spring.xml` sort du texte brut : Promtail la collecte, mais
  rien n'est corrélable — **les images applicatives doivent être reconstruites**.
  Détail complet : `ansible/roles/observability/README.md`.
- ~~**Le dashboard n'est pas conteneurisé**~~ **Corrigé** : Dockerfile (build Node,
  runtime `nginx-unprivileged` non-root), fragment Compose et route Traefik.
- **`payment-service` et `travel-service` désactivés par défaut** dans les defaults
  du rôle `compose-assembly` (`assembly_payment_enabled` / `assembly_travel_enabled`
  à `false`) : un run réel doit les passer à `true` (`-e`), comme pour
  `assembly_identity_enabled`. Elasticsearch reste, lui, derrière son propre profil
  `search`.
- ~~Pas de rôle `observability`~~ → **le rôle `observability` existe et est testé**
  (Loki + Promtail + Grafana, scénario Molecule complet : 28 assertions,
  idempotence `changed=0`). ~~Il reste à inclure dans le Compose assemblé.~~
  **Corrigé** : `compose-assembly` inclut désormais les fragments `sonarqube` et
  `observability` derrière `assembly_sonarqube_enabled` /
  `assembly_observability_enabled`, applique `profiles: [ci]` /
  `profiles: [observability]` sur leurs services, et **ajoute ces profils à la liste
  de `traefik`** — sans quoi Grafana et SonarQube démarreraient sans la gateway qui
  les route, `healthy` et injoignables. Les deux secrets correspondants
  (`SONARQUBE_DB_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`) passent par Vault →
  `app-secrets` → `.env`, comme tous les autres : plus aucun `export` shell.
- Pas de rôle `common` (annoncé dans `ansible/README.md`, n'existe pas).
- **Kubernetes (bonus du sujet) : démonstration seulement.** `k8s/` contient des
  manifestes (namespace, Postgres, Neo4j, Vault, 3 services, Ingress) testés sur
  `kind`, **à côté** d'Ansible/Compose qui restent le déploiement réel. Pas de
  Elasticsearch, de dashboard ni de CI/CD côté Kubernetes.

### Process
- **Workflow par branche : en place, mais sans plateforme de revue.** Une branche
  par feature/correctif, intégrée dans `main` par un merge `--no-ff` (le graphe
  `git log --graph` montre chaque branche). Les revues ont lieu en local (skill
  `/code-review`) ; il n'y a **pas de Pull Requests GitHub** approuvées par un tiers,
  ce que le sujet et la grille d'audit demandent.
- **Tests** : intégration Testcontainers côté backend, Vitest (unitaires) et
  Playwright (e2e) côté front — voir « Tests existants ». Pas de test de charge
  (la cible « < 5 s sous forte charge » de la grille n'est pas mesurée) ni de test de
  panne automatisé au-delà du failover Traefik entre réplicas.

### Documentation
- Les README de `ansible/`, `services/`, `services/admin-dashboard/` et des trois
  services sont alignés sur l'état réel ; `ci/jenkins/README.md` et
  `ci/sonarqube/README.md` sont des notes d'orientation (la config vit dans les rôles
  Ansible). Les décisions Phase 1 sont dans `docs/lets-travel-architecture-decisions.md`.
- Conformité RGPD / protection des données : aucune analyse formelle n'a été
  produite (soft-delete et hachage BCrypt ne suffisent pas à l'établir) ; la grille
  d'audit la demande.
