# Rôle `observability` — logging centralisé (Loki + Promtail + Grafana)

Provisionne le stack de **logging centralisé** de Travel-Plan sous forme d'un
fragment Compose du profil dédié `observability`.

Modèle opératoire identique aux autres rôles du projet :
**Ansible provisionne et rend des fichiers, docker-compose orchestre.**
Le rôle ne démarre aucun conteneur, ne pose aucun sysctl, et n'écrit aucun secret.

Le détail de chaque valeur et de chaque arbitrage est commenté ligne à ligne dans
`defaults/main.yml` et les quatre templates. Ce README couvre ce qui ne peut pas
y tenir : le partage des responsabilités avec le code applicatif, les procédures
manuelles, les mesures, et **le résultat réel de la vérification bout-en-bout**.

---

## 1. Qui fait quoi — à lire avant de chercher un bug au mauvais endroit

Le logging distribué de ce projet se joue à **deux** endroits, et ce rôle n'en
couvre qu'un :

| Étage | Qui | Où |
|---|---|---|
| **Émettre** du JSON portant un `requestId` | code applicatif Spring | `services/*/src/main/resources/logback-spring.xml`, `services/*/.../filter/RequestIdFilter.java` |
| **Propager** le `requestId` d'identity vers payment | code applicatif Spring | `PaymentServiceClient` (en-tête `X-Request-Id`) |
| **Collecter, stocker, exposer** | **ce rôle** | `ansible/roles/observability/` |

Conséquence directe et non négociable : **ce stack n'a d'intérêt que face à des
images de services construites après l'ajout du logging JSON.** Branché sur des
images antérieures, Promtail collecte bien les lignes — mais en texte brut, et
`| json | requestId="…"` ne filtre rien. Ce n'est pas une panne du stack, c'est
une image applicative trop ancienne. C'est exactement le piège rencontré pendant
la vérification décrite en §6.

---

## 2. Le stack en une phrase

Promtail lit les logs des conteneurs applicatifs **via l'API Docker** (socket
monté en lecture seule), les pousse dans Loki, et Grafana — seul composant routé
par Traefik, seul composant qui authentifie — sert de fenêtre de lecture.

```
conteneurs applicatifs ──(API Docker, :ro)──> Promtail ──push──> Loki
                                                                   │
  navigateur ──https://grafana.localhost──> Traefik ──> Grafana ────┘
```

**Loki et Promtail ne sont routés par rien et ne publient aucun port.** Ce n'est
pas de la prudence décorative : l'API de Loki tourne avec `auth_enabled: false`
(le multi-tenant de Loki est un séparateur de locataires, **pas** un contrôle
d'accès). Lui poser une route Traefik exposerait l'intégralité des logs
applicatifs en lecture **et en écriture** à quiconque atteint la gateway. Ils
vivent donc sur un réseau `internal: true` sans route sortante, et le seul accès
humain passe par Grafana.

---

## 3. Profil Compose dédié — la décision et son chiffrage

Ce stack a son **propre profil**, `observability`, **additif** aux autres :

```bash
docker compose --profile full --profile observability up -d
```

Pourquoi pas ailleurs :

- **Pas `core`.** Le dev quotidien (~350 Mo) est calibré pour laisser la place à
  un IDE et un navigateur. +450 Mo en permanence pour des logs qu'on ne lit pas
  tous les jours romprait ce calibrage.
- **Pas `full`.** C'est le profil de démo/intégration, et on n'a pas toujours
  besoin de lire les logs pendant une démo.
- **Pas `ci`.** Ce stack observe la stack **applicative**. Le monter avec Jenkins
  et SonarQube, alors que les services ne tournent pas, ne collecterait rien.

### Budget RAM mesuré (`docker stats`, machine réelle)

| Poste | Mesuré | `mem_limit` |
|---|---|---|
| Loki | 84 Mo | 512m |
| Promtail (6 conteneurs suivis) | 31 Mo | 128m |
| Grafana (à vide) | 336 Mo | 512m |
| **Total stack observability** | **~450 Mo** | |

À comparer, mesuré sur la même machine au même moment :

| Poste | Mesuré |
|---|---|
| Stack applicative complète (6 services répliqués + postgres + neo4j + vault + traefik) | ~1,69 Go |
| SonarQube seul | 2,09 Go |
| Base dédiée SonarQube | 122 Mo |
| Jenkins (`mem_limit` déclaré, non mesuré ici) | 1,6 Go |

**Lecture.** Sur les 8 Go documentés : `full` + `observability` ≈ **2,15 Go**,
parfaitement finançable. `full` + `ci` + `observability` ≈ **5,9 Go**, ce qui ne
laisse plus rien à l'IDE — d'où trois profils séparés et composables plutôt
qu'un gros profil « tout ».

Ce qui rend ce stack finançable, c'est qu'il est fait de **trois binaires Go,
sans aucune JVM**. Un ELK à la place, avec son Elasticsearch, coûterait à lui
seul plus que SonarQube.

> **Note de mesure, à ne pas laisser passer.** La machine sur laquelle ces
> chiffres ont été relevés a **14,8 Go**, pas 8. Les 8 Go restent la contrainte
> de conception documentée du projet (`docs/architecture-decisions.md`) et tous
> les arbitrages ci-dessus sont faits contre ce budget-là. Mais les
> *pourcentages de saturation* observés en direct sur cette machine sont plus
> confortables que ce que vivra une machine à 8 Go : ne pas les confondre.

### Un `mem_limit` corrigé par la mesure

Grafana était initialement plafonné à `384m`. Mesure : **336 Mo à vide, sans un
seul dashboard ouvert**, soit 88 % d'occupation avant toute requête. Relevé à
`512m`. Grafana embarque un runtime Node pour son rendu côté serveur en plus du
binaire Go — c'est la mesure qui l'a montré, pas une intuition.

---

## 4. Raccordement au reste de la stack — câblé

### Mot de passe admin Grafana

Le fragment ne rend **que la référence** `${GRAFANA_ADMIN_PASSWORD:?…}` ; la
valeur vient de `/opt/travel-plan/.env`, dont le rôle **`app-secrets`** est le
propriétaire **unique** (deux rôles templatant le même fichier s'écraseraient à
chaque run — idempotence violée).

Ce secret suit désormais la chaîne standard du projet, comme tous les autres :

```
vault/defaults/main.yml   secret/observability/grafana  (clé `password`)
        ▼
app-secrets               app_secrets_vault_path_observability_grafana
        ▼
/opt/travel-plan/.env     GRAFANA_ADMIN_PASSWORD=...
        ▼
Compose au `up`           GF_SECURITY_ADMIN_PASSWORD
```

```bash
ansible-playbook ansible/roles/vault/test-local.yml --tags provision \
  -e vault_addr=http://<ip-conteneur-vault>:8200
ansible-playbook ansible/roles/app-secrets/test-local.yml \
  -e app_secrets_vault_read=true -e vault_addr=http://<ip-conteneur-vault>:8200
```

`vault/defaults/main.yml` ne porte qu'un placeholder `changeme_grafana_admin` :
la valeur réelle se fournit en surcharge (`-e`, ou `ansible-vault`), jamais dans
le dépôt.

Le `:?` de `${GRAFANA_ADMIN_PASSWORD:?…}` n'est pas décoratif : si la variable
manque, **`docker compose up` refuse de démarrer** au lieu de laisser Grafana
retomber sur son `admin/admin` par défaut. Une UI d'observabilité ouverte à tout
venant derrière la gateway est un incident ; un démarrage qui refuse de partir
est un message.

> **Comportement propre à Grafana, à connaître.** `GF_SECURITY_ADMIN_PASSWORD`
> n'est appliqué qu'à la **première** initialisation de la base interne
> (`grafana-data`). Changer la valeur du secret sur un volume existant ne rejoue
> **pas** le mot de passe : il faut le changer dans l'UI, ou supprimer le volume.
> Contrairement au cas SonarQube, rien ne casse — c'est seulement le mot de passe
> de connexion qui reste l'ancien.

### Inclusion dans le Compose assemblé

`/opt/travel-plan/docker-compose.yml`, rendu par le rôle **`compose-assembly`**,
inclut désormais `observability/compose.observability.yml` derrière le flag
`assembly_observability_enabled` (`false` par défaut : un `include:` est résolu
quel que soit le `--profile`, donc un fragment absent du disque casserait aussi
le rendu de `core` et `full`).

Côté `compose-assembly` :

- `include:` de `observability/compose.observability.yml` (chemin **relatif**) ;
- merge `profiles: [observability]` sur `loki`, `promtail` **et** `grafana` ;
- `observability` **ajouté à la liste de profils de `traefik`** — sans quoi
  `--profile observability` seul monterait Grafana sans la gateway qui le route,
  et l'UI serait injoignable. Le piège est silencieux : le conteneur démarre,
  passe `healthy`, simplement personne ne peut l'atteindre. Même raisonnement
  que celui déjà appliqué pour l'UI Jenkins.

```bash
ansible-playbook ansible/roles/compose-assembly/test-local.yml -K \
  -e assembly_observability_enabled=true [...autres flags...]

# plus aucun -f manuel :
docker compose --profile full --profile observability up -d
```

**Vérifié réellement** : `docker compose --profile observability config
--services` rend `grafana loki promtail traefik`, et
`https://grafana.localhost` répond 302 (redirection de login) à travers Traefik.

---

## 5. Anomalie assumée : Loki n'a pas de healthcheck

C'est le seul service du projet sans sonde. **Ce n'est pas un oubli.**

L'image `grafana/loki` est **distroless**. Vérifié, pas supposé : l'export de son
système de fichiers ne contient qu'**un seul binaire**, `/usr/bin/loki`. Ni
shell, ni `wget`, ni `curl`, ni busybox. Un `test: ["CMD-SHELL", …]` y échoue
avec `stat /bin/sh: no such file or directory`, ce qui marque le conteneur
**unhealthy en permanence** et bloque tout `depends_on: service_healthy`.
C'est exactement ce qui s'est produit au premier démarrage du stack.

Les trois issues, et pourquoi la troisième :

1. Construire une image dérivée pour y ajouter `wget` → une image maison à
   maintenir et re-construire à chaque montée de version amont, pour une sonde.
2. Retomber sur un tag non distroless → il n'en existe pas de stable et versionné
   pour Loki 3.x ; ce serait choisir un tag mouvant, interdit par le contrat.
3. **Ne pas sonder Loki et en assumer la conséquence.** Retenu.

**Conséquence réelle :** les `depends_on` qui visent Loki sont en
`service_started`. Promtail peut démarrer avant que Loki accepte des écritures —
sans perte de données (Promtail réessaie et garde sa position), au prix de
quelques erreurs de connexion dans son log de démarrage.

Le scénario Molecule **verrouille cet écart** par une assertion dédiée : si Loki
gagnait un healthcheck sans que ses dépendants repassent en `service_healthy`
(ou l'inverse), le test échoue.

> Note connexe : l'image `grafana/promtail` a bien un shell, mais **ni `wget` ni
> `curl`**. Sa sonde est donc une requête HTTP écrite à la main avec le
> pseudo-fichier `/dev/tcp` de bash. C'est inhabituel et c'est commenté comme tel
> dans le fragment.

---

## 6. Vérification bout-en-bout — résultat réel

Ce que Molecule **ne peut pas** prouver (il faut trois conteneurs vivants, le
socket Docker et une stack applicative qui émet) a été vérifié à la main. Voici
la séquence et **ce qu'elle a réellement donné**.

### 6.1 Collecte

Loki, interrogé sur les valeurs du label `container`, renvoie **exactement** les
six conteneurs applicatifs — et rien d'autre, alors que la machine en fait
tourner une quinzaine (dont des conteneurs d'autres projets). La liste blanche
de Promtail fait son travail :

```json
["travel-plan-identity-service-1","travel-plan-identity-service-2",
 "travel-plan-payment-service-1","travel-plan-payment-service-2",
 "travel-plan-travel-service-1","travel-plan-travel-service-2"]
```

Le label `service`, dérivé du label Compose, renvoie bien
`["identity-service","payment-service","travel-service"]` : les répliques `-1` et
`-2` se raisonnent ensemble.

### 6.2 Corrélation identity → payment : le résultat

Requête, une cascade `DELETE /users/{id}` émise avec un `X-Request-Id` connu,
puis interrogation de Loki :

```bash
curl -G "http://loki:3100/loki/api/v1/query_range" \
  --data-urlencode 'query={job="travel-plan"} | json | requestId=`cascade-…-corr`' \
  --data-urlencode "start=…" --data-urlencode "end=…"
```

**Résultat réel : 10 lignes, sur les deux services, pour un seul `requestId`.**
Reconstitué dans l'ordre chronologique :

```
identity-service-9  23:50:02.826  DELETE "/users/a61e3cdb-…"
identity-service-9  23:50:02.827  Mapped to …identity.controller.UserController#delete(UUID…)
payment-service-9   23:50:02.955  DELETE "/payments/by-user/a61e3cdb-…"
payment-service-9   23:50:02.962  Mapped to …payment.controller.PaymentController#deleteAll…
payment-service-9   23:50:03.129  Using 'application/json' …
payment-service-9   23:50:03.130  Writing [DeleteByUserResponse]
payment-service-9   23:50:03.138  Completed 200 OK
identity-service-9  23:50:03.150  Using 'application/json' …
identity-service-9  23:50:03.151  Nothing to write: null body
identity-service-9  23:50:03.151  Completed 204 NO_CONTENT
```

La cascade est lisible de bout en bout, dans les deux services, à partir d'un
seul identifiant. **Le logging distribué n'est plus une intention : il
fonctionne.**

### 6.3 Deux pièges rencontrés — à connaître

**a) Les images applicatives en cours d'exécution n'avaient pas le logging JSON.**
Les conteneurs `-1`/`-2` tournaient depuis des images construites **avant**
l'ajout de `logback-spring.xml`, et sortaient du texte brut. La vérification a
donc été faite sur deux répliques temporaires (`-9`) construites depuis les
sources à jour — lesquelles tombent naturellement dans la liste blanche de
Promtail, et ont été supprimées ensuite. **Tant que les images applicatives ne
sont pas reconstruites, ce stack collectera du texte brut non corrélable.**

**b) Une requête nominale n'écrit aucune ligne de log.** Premier essai : la
requête LogQL renvoie **0 ligne**, alors que tout le stack fonctionne. Cause
réelle : le `requestId` est bien dans le MDC, mais **aucun code applicatif
n'émet de log pendant une requête réussie** — un MDC sans instruction de log ne
produit rien.

La corrélation ci-dessus n'a donc été observable qu'en montant le niveau de log
web :

```
LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB=DEBUG
```

C'est **un constat sur le code applicatif, pas sur ce stack** : la plomberie
MDC → JSON → Promtail → Loki est démontrée correcte. Mais en l'état, à niveau
`INFO`, une requête nominale reste invisible dans Loki. Y remédier suppose
d'ajouter des logs métier (ou un filtre d'accès) dans les services — **du code
Java, hors du périmètre de ce rôle**, et signalé ici plutôt que masqué.

---

## 7. Interroger les logs

Depuis Grafana (`https://grafana.localhost` → Explore → datasource Loki) :

```logql
{job="travel-plan"}                                     # tout
{service="payment-service"}                             # un service, ses 2 répliques
{job="travel-plan"} |= "ERROR"                          # filtre texte brut, le plus rapide
{job="travel-plan"} | json | requestId=`<uuid>`         # LA requête de corrélation
{job="travel-plan"} | json | level="ERROR" | line_format "{{.message}}"
```

> **Pourquoi `requestId` n'est pas un label.** Dans Loki, chaque combinaison de
> labels crée un **flux** indexé. Le `requestId` est unique **par requête** : en
> faire un label ferait exploser le nombre de flux et mettrait Loki à genoux en
> quelques heures. Il reste dans la ligne et se filtre à la lecture — un peu plus
> lent à interroger, infiniment plus sain. Le scénario Molecule verrouille cette
> règle par une assertion.

---

## 8. Promtail est en fin de vie — dit franchement

Grafana a placé Promtail en maintenance et pousse **Grafana Alloy** comme
successeur. C'est donc un choix à durée limitée, retenu ici parce que sa
configuration `docker_sd_configs` fait exactement ce qu'on veut en ~20 lignes,
là où l'équivalent Alloy (`discovery.docker` + `loki.source.docker` +
`loki.process` en langage River) est plus verbeux pour un résultat identique à
cette échelle.

**Coût de la migration, le jour venu :** seuls `promtail-config.yml.j2` et le
bloc `promtail` du fragment changent. Loki, Grafana, la datasource et toutes les
requêtes LogQL sont **inchangés**. C'est tout l'intérêt du découplage.

---

## 9. Tests

```bash
# application locale (écrit sous /opt/travel-plan/observability/)
ansible-playbook ansible/roles/observability/test-local.yml -K

# idempotence + 28 assertions sur les 4 artefacts rendus
cd ansible/roles/observability && molecule test
```

**Résultat réel du dernier `molecule test` : 28 assertions OK, `changed=0` à
l'étape d'idempotence, séquence complète verte.**

Le scénario couvre l'intégralité de ce que le rôle *fait* (il ne rend que des
fichiers). Il ne prouve **pas** que Loki ingère, que Promtail découvre, ni que le
`requestId` est corrélable : ça exige trois conteneurs vivants, le socket Docker
et une stack applicative qui émet. C'est couvert par la §6, hors Molecule.
