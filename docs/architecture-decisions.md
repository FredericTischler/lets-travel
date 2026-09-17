# Travel-Plan — Décisions d'architecture (Phase 0)

> Document de tranchage. Chaque décision est **prise**, pas suggérée, et porte
> son tradeoff + ce qu'elle **sacrifie**. Rien n'est codé à ce stade.

## Contraintes posées (validées avec toi)

| Contrainte | Valeur | Impact structurant |
|---|---|---|
| RAM machine | **8 Go** | Mode dégradé obligatoire : la stack CI ne tourne **pas** en même temps que la stack applicative. 1 réplica par défaut, scale ponctuel pour la démo de LB/failover. Pas de broker lourd (Kafka/RabbitMQ), pas d'ELK. |
| Orchestration | **Docker Compose seul** (pas de K8s) | LB/failover via réplicas Compose + reverse-proxy. Bonus K8s sacrifié. |
| Stack | **Spring Boot (services) + Angular (dashboard)** | Dockerfiles JVM multi-stage, tests JUnit, intégration SonarQube native Maven/Sonar. |
| Secrets | **Vault serveur (dev mode)** | Conteneur Vault, secrets runtime lus au démarrage. Nuance bootstrap traitée §6. |

Ces choix sont le socle. Tout le reste en découle.

---

## 1. Découpage en microservices

La grille insiste : *« boundaries based on business domains »*, services *« independent »*,
*« deployed/updated/scaled without affecting others »*. Le découpage suit donc les
**bounded contexts métier**, pas les noms d'entités.

### La prémisse elle-même : microservices, pas un besoin d'archi

Soyons honnêtes avant de découper, exactement comme pour Neo4j (§2) : pour un **dashboard
admin CRUD mono-utilisateur développé en solo**, un **monolithe modulaire** serait
techniquement supérieur sur presque tous les axes — une seule JVM (RAM divisée par trois,
voir §4), transactions ACID cross-domaine sans cascade distribuée (§3), pas de gateway ni
de réseau à segmenter, déploiement et débogage triviaux. Les microservices ici n'adressent
**aucun besoin réel** de scaling, de déploiement indépendant ou d'équipes séparées : ils
sont une **contrainte de sujet / pédagogique**, pas un besoin d'architecture. On l'assume
sans détour. Tout le découpage ci-dessous est le *meilleur* microservices possible **sous
cette contrainte** — il ne prétend pas que les microservices étaient nécessaires.

### Décision : 3 services métier + 1 front + 1 gateway

| Service | Bounded context | Frontière métier | Store |
|---|---|---|---|
| **identity-service** | Identity & Access Management | Authentification, autorisation/RBAC, émission/validation JWT, **et le CRUD des utilisateurs** que l'admin gère. | PostgreSQL |
| **travel-service** | Itinéraires de voyage | Travels, destinations (1..n), activités, hébergements, transports, relations entre destinations. | Neo4j |
| **payment-service** | Paiement | Méthodes de paiement, transactions, intégration Stripe + PayPal. | PostgreSQL |
| **admin-dashboard** | Front d'administration | UI responsive (Chrome/Firefox), consomme l'API via la gateway. | — (statique) |
| **traefik (gateway)** | Infra, pas un domaine | Point d'entrée unique, TLS, routage, LB, authN déléguée. Voir §6. | — |

### Le choix qui mérite défense : fusion auth + utilisateurs dans `identity-service`

Le sujet parle séparément d'« authentication and authorization service » et de
« manage users ». La tentation serait deux services (`auth` + `user`). **Je les fusionne.**

- **Pourquoi.** AuthN/Z et cycle de vie utilisateur sont **un seul bounded context**
  (IAM) : le même agrégat « User » porte credentials, rôles ET profil. Les séparer
  crée un couplage permanent (toute opération utilisateur touche les deux) — l'inverse
  de l'indépendance que la grille récompense.
- **Bénéfice cascade.** L'entité la plus « cascade-lourde » (supprimer un user →
  purger credentials + rôles) reste **dans une seule base** → cascade FK PostgreSQL
  native, pas de saga distribuée. Voir §3.
- **Bénéfice RAM.** Une JVM de moins (~400 Mo) sur une machine à 8 Go.
- **Tradeoff / ce que je sacrifie.** Granularité « un service par nom métier ».
  Les préoccupations d'auth et de profil cohabitent. Si demain le profil utilisateur
  explose en complexité (préférences, historique, KYC…), il faudra extraire un
  `profile-service` — coût de refactor assumé plus tard plutôt que sur-découpage maintenant.
- **Alternative rejetée :** 4 services (`auth` + `user` séparés). Plus « propre » sur
  le papier, mais introduit un cross-service cascade sur l'entité la plus fréquente et
  une JVM supplémentaire pour zéro gain métier à ce stade. **Tranché : on reste à 3,
  fusion `identity` confirmée** (cf. §10 — ce n'est plus un point ouvert).

### Pourquoi pas plus de services ?

3 services métier + 2 technos de base + gateway est un setup microservices légitime.
La grille pénalise le découpage arbitraire (« based on business domains »). La quantité
n'est pas un critère ; la **netteté des frontières** l'est. Sacrifice : on n'impressionne
pas par le nombre.

### Indépendance / scalabilité / résilience (exigences explicites de la grille)

- **Déploiement indépendant** : une image Docker par service, un rôle Ansible de déploiement
  paramétré par service (§4). Mettre à jour `payment-service` ne touche pas les autres.
- **Scalabilité indépendante** : `docker compose up --scale payment-service=N`. Chaque
  service scale séparément, Traefik découvre les réplicas automatiquement (§5).
- **Résilience (avec une réserve majeure)** : si `travel-service` **ou** `payment-service`
  tombe, `identity` et l'autre service répondent toujours ; le dashboard dégrade la section
  concernée. **MAIS** `identity-service` est un **point de défaillance unique au runtime** :
  toute requête est authentifiée via ForwardAuth délégué à identity (validation JWT, voir
  §6), donc s'il tombe, **plus rien ne s'authentifie et toute la stack devient
  inaccessible**. Le découplage synchrone ne vaut donc que pour travel ↔ payment, pas
  vis-à-vis d'identity. SPOF documenté et assumé en §6.

---

## 2. Répartition des données — PostgreSQL vs Neo4j

Le sujet **impose** les deux. La vraie question : Neo4j est-il *justifié* ou décoratif ?

### Décision

- **PostgreSQL** — tout ce qui est relationnel, transactionnel, ACID-critique :
  - `identity-service` : users, credentials, rôles, permissions (RBAC).
  - `payment-service` : méthodes de paiement, transactions, références gateway (Stripe/PayPal).
  - Justification : intégrité référentielle, contraintes, **cascades FK**, et surtout
    l'argent → on ne plaisante pas avec la cohérence.

- **Neo4j** — `travel-service` uniquement, **parce que le sujet l'impose**. La vraie
  question n'est pas « faut-il Neo4j ? » (oui, c'est imposé) mais « la donnée s'y prête-t-elle,
  et l'exploite-t-on à ce stade ? ». Réponse honnête : la forme s'y prête en partie, le
  workload non.
  - **Ce qui a réellement une forme graphe** : les **trajets inter-destinations**
    (`(Destination)-[:TRANSPORT {mode,duration}]->(Destination)`) — des arêtes entre nœuds
    de même nature. C'est la seule structure que le relationnel modélise avec un peu de friction.
  - **Ce qui n'en a pas** : `(Travel)-[:HAS_DESTINATION]->(Destination)`,
    `(Destination)-[:HAS_ACTIVITY]->(Activity)`, `(Destination)-[:STAYS_AT]->(Accommodation)`
    relèvent de la **containment hiérarchique** — du parent → enfants que le relationnel
    gère sans douleur (clés étrangères, `ON DELETE CASCADE`).
  - Bonus cascade : `DETACH DELETE` supprime un nœud et toutes ses relations d'un coup.

### Honnêteté intellectuelle : on stocke un graphe qu'on n'est pas encore sommé de parcourir

La **partie 1** du sujet est du **CRUD admin**. **Aucun workload de traversée** n'exploite
le graphe à ce stade : pas de plus-court-chemin, pas de recommandation, pas de parcours
multi-sauts. Un modèle **relationnel** avec une simple table `destination_transport`
(couple de destinations + mode + durée) couvrirait la partie 1 **à l'identique**, sans une
ligne de Cypher. La justification réelle de Neo4j n'est donc **pas les requêtes actuelles**
(il n'y en a pas), mais :

1. la **forme de la donnée** (les arêtes de transport sont nativement un graphe), et
2. un **workload futur probable** — une fonctionnalité de recommandation / parcours dans
   une partie ultérieure du sujet, où Cypher deviendrait idiomatique là où SQL ferait des
   jointures récursives pénibles.

**Décision : oui à Neo4j, mais comme dette consciente.** On l'assume parce que le sujet
l'impose et que le futur le rendra *probablement* utile — **pas** parce qu'une nécessité
présente l'exige. **Ce que je sacrifie** : la simplicité d'un mono-store PostgreSQL, plus
~600 Mo–1 Go de RAM (JVM Neo4j) sur une machine à 8 Go (§4 — c'est le plus gros poste
variable), pour une base dont on n'exploite,
en partie 1, qu'une fraction des capacités. Coût payé d'avance contre une valeur future
pariée, et nommé comme tel.

---

## 3. Cascades update / delete — où elles vivent

Le sujet l'exige explicitement (« think about database cascading update and delete »).
En microservices, **chaque service possède sa base → pas de FK inter-services**. Les
cascades se découpent donc en deux niveaux.

### Principe directeur : soft-delete partout

**Décision transverse :** chaque entité (user, travel, destination, payment_method,
transaction…) porte un champ **`deleted_at`**. « Supprimer » = **poser le flag**, jamais
purge physique en première intention. Conséquence majeure : **rien n'est jamais
physiquement perdu**, donc **toute suppression est réversible**. C'est ce qui désamorce
toute la complexité des cascades inter-services (Niveau 2).

**Contrainte grille à respecter :** du point de vue admin, un élément soft-deleted doit
**se comporter exactement comme supprimé** — il doit **disparaître de toutes les lectures
et de toutes les listes**. On filtre donc `WHERE deleted_at IS NULL` (Postgres) /
`WHERE n.deleted_at IS NULL` (Cypher) sur **tous les reads, sans exception**.

### Niveau 1 — Intra-service (dans une seule base)

Supprimer un agrégat pose le flag sur la racine **et sur ses enfants**, dans la même
transaction / le même service :

| Contexte | Mécanisme soft-delete |
|---|---|
| identity (Postgres) | Poser `deleted_at` sur user **et** ses credentials / rôles / permissions (cascade applicative dans une transaction). |
| payment (Postgres) | Poser `deleted_at` sur payment_method **et** ses transactions ; sur les payment_methods d'un user. |
| travel (Neo4j) | Poser `deleted_at` sur le nœud Travel **et** ses Destinations / Activities / Accommodations rattachées. |

> La cascade FK native (`ON DELETE CASCADE`) et `DETACH DELETE` restent disponibles mais
> **réservées à une éventuelle purge physique différée** (hors première intention), pas au
> flux « delete » de l'admin.

### Niveau 2 — Inter-services : propagation de flag

Supprimer un utilisateur dans `identity-service` doit faire disparaître **ses voyages**
(travel) et **ses paiements** (payment). Aucune FK ne traverse les services — mais comme
on ne fait que **poser un flag**, il n'y a plus rien de destructif à orchestrer.

- **Décision : propagation de flag, best-effort réconcilié.** L'opération admin « delete
  user » fait que identity pose son propre `deleted_at`, puis appelle travel-service et
  payment-service (`POST /internal/by-user/{id}/soft-delete`) pour qu'ils posent les leurs
  sur les agrégats de cet utilisateur.
- **Échec partiel = non-dramatique.** Si l'appel à payment échoue, identity et travel ont
  déjà leur flag ; on **rejoue** (retry) ou on **réconcilie** (job balayant les users
  soft-deleted dont les paiements ne le sont pas encore). **Aucune saga compensatoire
  destructive** n'est nécessaire : rien n'a été détruit, donc rien n'a à être « annulé » —
  tout est réversible par nature.
- **Tradeoff / ce que je sacrifie.** (1) **Croissance des tables** : les lignes
  soft-deleted s'accumulent (purge physique différée possible plus tard, hors MVP).
  (2) **Discipline de filtrage** : oublier un `WHERE deleted_at IS NULL` sur un read
  ferait réapparaître un élément censé être supprimé — risque à tenir sur **chaque**
  lecture. On échange la complexité d'une orchestration destructive contre une discipline
  de lecture systématique.
- **Pas de broker de messages.** Kafka/RabbitMQ restent **écartés** (trop lourds pour
  8 Go) ; la propagation est un simple appel HTTP interne idempotent. Si un broker léger
  (NATS ~20 Mo) devient acceptable, `UserSoftDeleted` deviendrait un événement de domaine ;
  la frontière `/internal` est déjà isolée pour ce passage.

C'est le point d'architecture le plus subtil du projet ; le soft-delete le rend
**traitable sans transaction distribuée**, au prix d'une rigueur de filtrage.

### Le piège du soft-delete : unicité (DÉCISION de schéma)

Le soft-delete a un piège qui touche **directement l'audit CRUD** (« create, delete, then
recreate ») : une contrainte `UNIQUE` s'applique au niveau base **même sur une ligne
soft-deletée**. Recréer un user avec le même email **explose sur la ligne morte**. Parade,
actée comme décision de **schéma** (donc Phase 0) :

- **Postgres** : **index unique PARTIEL** — `UNIQUE(email) WHERE deleted_at IS NULL` (idem
  pour tout champ naturellement unique). La contrainte ne porte que sur les lignes vivantes ;
  recréer après suppression passe.
- **Neo4j** : **pas d'équivalent natif propre** (les constraints d'unicité ignorent le
  flag) → **unicité gérée applicativement** sur les seuls nœuds non soft-deletés.
- **Note de cohérence (lien §2)** : les nœuds Neo4j soft-deletés **conservent leurs
  relations** (`DETACH DELETE` réservé à la purge), donc le **futur workload de traversée**
  — celui qui justifie Neo4j (§2) — devra **filtrer `deleted_at` à chaque saut**, pas
  seulement sur le nœud d'entrée.

---

## 4. Topologie réseau Docker & réplicas / load-balancing

### Budget RAM — l'addition posée (et pas seulement affirmée)

On répète « 8 Go, c'est serré » sans jamais poser l'addition. Précision décisive : **ces
8 Go sont la RAM de MA machine de DEV, sous Linux natif** — Docker partage le noyau, pas
de VM type Docker Desktop, donc l'overhead hôte reste faible (~1 Go OS + démon). C'est ce
qui rend la suite **vivable plutôt qu'impossible**. Voici l'addition, estimation par poste
**au repos** (1 réplica chacun, RSS approximatif) :

| Poste | RAM au repos (est.) | Note |
|---|---|---|
| OS + démon Docker (Linux natif) | ~1 Go | Pas de VM Docker Desktop → overhead faible. |
| Traefik (binaire Go) | ~70 Mo | Léger. |
| identity-service (JVM) | ~550 Mo | `-Xmx256m` **+ non-heap Spring** (metaspace, stacks, code cache, buffers : 150–250 Mo). |
| travel-service (JVM) | ~550 Mo | Idem + driver Neo4j. |
| payment-service (JVM) | ~550 Mo | Idem. |
| Neo4j (JVM) | ~600 Mo–1 Go | Heap 512m + page cache ~256m ; **plus gros poste variable**. |
| PostgreSQL (1 instance, 2 bases) | ~250 Mo | identity + payment. |
| Vault (dev mode) | ~60 Mo | En mémoire. |
| Loki + Promtail + Grafana | **~450 Mo mesurés** | Optionnel, profil dédié (§7). Estimation initiale ~180 Mo : elle **oubliait Grafana**, qui pèse à lui seul 336 Mo à vide (runtime Node de rendu serveur en plus du binaire Go). |
| **Total stack au repos** | **~3,8 Go** | Stack runtime seule, rien de scalé. |

**La conclusion « ~4,5 Go libres » de la version précédente était FAUSSE en contexte
machine de dev** — car je **développe sur la machine qui fait tourner la stack**. Il faut
donc ajouter à l'addition :

| Poste dev | RAM (est.) | Pourquoi |
|---|---|---|
| Navigateur | ~1 Go | Grille : dashboard testé sur **Chrome ET Firefox** → deux navigateurs ouverts. |
| IDE (si je code en parallèle) | ~2 Go | Pics au build Node/Angular. |

**Total réaliste : ~6,5–7 Go occupés *avant tout scale*** → le modèle « toute la stack up
en permanence pendant que je code » est **intenable**. D'où la décision profils Compose
ci-dessous. L'addition confirme aussi que la stack CI (Jenkins ~700 Mo + SonarQube ~1,5 Go)
**ne peut pas** coexister (§8), et que ~~1 réplica par défaut est obligatoire~~ le nombre de
réplicas doit rester **réglable** (plus bas) : le défaut est passé à **2 réplicas** par service
applicatif (~+850 Mo mesurés pour les trois), redescendable à 1 via `*_REPLICAS=1` sur un poste
qui ne suit pas — sans éditer les fragments Compose.

**Décision critique — `-Xmx` fixé explicitement sur CHAQUE JVM.** Par défaut, une JVM
moderne réserve **25 % de la RAM hôte** (`MaxRAMPercentage`) comme heap **si aucune limite
mémoire conteneur n'est imposée**. Sur 8 Go → **~2 Go de heap par JVM**. Avec 3 services
Spring **+ Neo4j**, les défauts mènent à une **saturation dès le 3ᵉ conteneur**. On fixe
donc, sans exception :

| Service | Réglage mémoire |
|---|---|
| identity-service | `-Xmx256m` (CRUD léger) |
| travel-service | `-Xmx256m` |
| payment-service | `-Xmx256m` |
| Neo4j | heap max **512m** + page cache **256m** (variables `NEO4J_*`, pas `-Xmx`) |

En complément, une **limite `mem_limit` Docker** par conteneur, pour que le défaut
container-aware de la JVM reste borné même si un `-Xmx` venait à être oublié.
**Tradeoff / sacrifice** : des heaps volontairement petits → moins de marge avant
`OutOfMemoryError` sous charge et un GC plus fréquent. Acceptable pour une démo CRUD : on
échange la performance sous charge contre la **capacité à faire tenir toute la stack dans
8 Go**.

### Profils Docker Compose — fin du « tout up en permanence » (DÉCISION)

Conséquence directe du budget ci-dessus : le modèle n'est **plus** « toute la stack en
permanence ». On découpe via **profils Docker Compose** :

| Profil | Contenu | Usage |
|---|---|---|
| **core** | traefik + identity + postgres + **le seul service en cours de dev** ; Neo4j et les autres JVM restent à terre. | **Mode dev QUOTIDIEN** — ~1,5 Go, laisse la place à IDE + navigateur. |
| **full** | tout up | Tests d'intégration + répétition de démo, **IDE fermé**. |
| **observability** | Loki + Promtail + **Grafana** | **OFF par défaut**, **additif** : `--profile full --profile observability`. ~450 Mo **mesurés** (§7, livré). |
| **ci** | Jenkins / SonarQube | À la demande — **même mécanisme de profil** que §8. |

- **Neo4j est le plus gros poste variable** (~600 Mo–1 Go) : inutile up pour bosser sur
  identity ou payment → **~1 Go récupéré gratuitement** en profil `core`.
- **Tradeoff / ce que je sacrifie** : on **ne teste pas tout le système en continu**. Il
  faut basculer de profil et accepter que le dev quotidien tourne sur un **sous-ensemble**
  de la stack. On échange la commodité du « tout visible tout le temps » contre la seule
  configuration qui tienne dans 8 Go **en codant**.

### Réseaux (segmentation = principe de moindre privilège réseau)

Trois réseaux bridge, cloisonnés :

```
edge-net      : traefik  (+ exposition HTTPS vers l'hôte, seul point public)
backend-net   : traefik  ⇄ identity / travel / payment / dashboard
data-net      : identity ⇄ postgres | payment ⇄ postgres | travel ⇄ neo4j | * ⇄ vault
```

- Les **bases ne sont jamais exposées à l'hôte** (exigence sujet : « accessible only within
  the internal network »). Elles vivent sur `data-net`, joignables uniquement par leur service
  propriétaire.
- Vault est sur `data-net`, atteignable par les services qui lisent leurs secrets, pas depuis l'extérieur.
- Seul Traefik a un port publié (443). Tout le reste est interne.
- **Tradeoff / sacrifice** : segmentation à 3 réseaux = un peu plus de config Compose qu'un
  réseau unique à plat. On sacrifie la simplicité d'un seul réseau au profit du moindre privilège,
  que la grille audite explicitement.

### Réplicas & load-balancing

- **Décision** : LB via réplicas Compose (`--scale`) + **Traefik** comme load-balancer.
  Traefik découvre les conteneurs via les labels Docker → quand on scale un service,
  les nouvelles instances entrent dans le pool **sans reconfiguration**.
- **Failover** : Traefik retire une instance morte du pool (healthcheck). Si une réplique
  tombe, le trafic part vers les autres.
- **Réglage 8 Go** : ~~**1 réplica par défaut** partout ; on ne maintient pas N réplicas en
  permanence, la RAM ne suit pas.~~
  **RÉVISÉ (implémentation)** : les trois services applicatifs portent désormais
  `deploy.replicas` à **2 par défaut**. Le prérequis a été levé : le `container_name`
  fixe (`travel-plan-identity`/`-payment`/`-travel`) a été **retiré** des trois
  fragments — il est unique par daemon Docker et interdisait structurellement toute
  2ᵉ instance. Coût mesuré : ~280 Mo par réplica JVM, soit ~+850 Mo pour passer les
  trois services de 1 à 2. Reste tenable, mais le budget 8 Go n'est plus confortable
  avec `full` + Neo4j : le nombre de réplicas est donc **interpolé**
  (`IDENTITY_REPLICAS` / `PAYMENT_REPLICAS` / `TRAVEL_REPLICAS`), permettant de
  redescendre à 1 sans éditer les fragments. Le mécanisme est en place par défaut ;
  le réglage reste une variable d'exploitation, pas une réécriture.
- **Démo failover (stratégie Linux natif 8 Go)** : profil **`full`**, **IDE fermé**, scaler
  **UN SEUL** service à **2** (pas 3), puis couper une instance pour montrer le report de
  trafic. Le **swap** sert de filet sur les pics. **À répéter avant le jour J** pour
  confirmer que ça tient. Rappel (§6) : on coupe **travel ou payment**, **JAMAIS identity**
  (SPOF — sa chute couperait l'authN de toute la stack).
- **Tradeoff / sacrifice** : ce n'est pas de la haute dispo réelle 24/7. On sacrifie le « always-on
  multi-réplica » faute de RAM ; on garde la **démontrabilité** du mécanisme. Honnête vis-à-vis
  de la grille : le mécanisme existe et se prouve, il n'est juste pas dimensionné production.
  **Mise à jour** : le multi-réplica est maintenant l'état *par défaut* (2 instances), plus
  seulement « démontrable ». Le sacrifice résiduel est plus étroit : pas de réplication des
  **bases** (Postgres et Neo4j restent des singletons, donc SPOF de données), et le scaling
  reste manuel — pas d'autoscaling, ce qui relèverait de K8s (hors périmètre, bonus séparé).
- **Vérification empirique (faite, pas supposée)** : avec 2 réplicas `identity-service`,
  40 requêtes via Traefik se répartissent quasi à parité entre les deux instances
  (11 774 vs 11 914 octets reçus) ; instance n°2 arrêtée, **20/20** requêtes restent en
  `200`. Round-robin et failover confirmés, sans aucune modification de la config Traefik :
  le provider Docker agrège tous les conteneurs portant les mêmes labels
  `traefik.http.services.<nom>.loadbalancer.*` dans un pool unique.

### Dette de test : ce budget est estimé, pas mesuré

**Ce n'est pas une décision, c'est une vérification empirique à faire.** Tout le budget RAM
ci-dessus est **estimé** (RSS approximatif), **pas mesuré**. Avant de considérer la Phase 0
close pour de bon, une vérif s'impose **dès que la stack `full` sera debout en Phase 1** :
`docker stats` sous profil **`full` + un service scalé à 2**, **IDE fermé**, **navigateur
ouvert**, pour confirmer que le RSS réel tient dans 8 Go avec le **swap en filet**. Si le
réel dépasse trop l'estimé, on rabote : Loki off, heaps plus petits, ou démo en deux temps.
**Dette à lever par la MESURE, pas par le raisonnement.**

---

## 5. Secrets (Vault) & SSL/TLS — périmètre minimal viable

### Vault — décision : serveur en dev mode

- Conteneur **Vault dev mode**. Les services y lisent au démarrage : creds Postgres/Neo4j,
  clés API Stripe & PayPal.
- Provisionnement : un rôle Ansible `vault` écrit les secrets ; les services s'authentifient
  (token / AppRole).
- **Nuance bootstrap (honnêteté requise par l'agent infra).** Vault dev mode est **non scellé,
  en mémoire, non persistant** — adapté au dev, pas à la prod. De plus il y a un œuf-poule :
  Ansible a besoin d'un token initial pour peupler Vault → ce **seul** token de bootstrap est
  chiffré via **ansible-vault**. Tout le reste des secrets runtime vit dans le serveur Vault.
  Ce n'est pas une trahison du choix « Vault serveur » : c'est le minimum incompressible pour amorcer.
- **Tradeoff / sacrifice** : dev mode = pas de persistance ni de scellement → on sacrifie le
  réalisme prod (auto-unseal, HA Vault) contre la simplicité et la RAM. Assumé pour un projet école.

### SSL/TLS — décision : terminaison TLS au bord (Traefik)

- **TLS terminé chez Traefik** (certs auto-signés / mkcert en dev). Unique surface HTTPS publique.
- **Trafic interne** (backend-net, data-net) en clair, **réseau Docker considéré de confiance**.
- **Périmètre minimal viable** = chiffrer tout ce qui sort de la machine (in transit, exigence sujet) ;
  ne pas chiffrer l'intra-cluster.
- **Tradeoff / sacrifice** : pas de **mTLS** entre services (nécessiterait un service mesh →
  trop lourd pour 8 Go). On sacrifie le chiffrement interne service-à-service ; mitigé par la
  segmentation réseau (§4) et l'absence d'exposition externe des services internes.

### Moindre privilège (exigence grille)

- RBAC applicatif dans identity-service (rôle ADMIN requis pour toute opération du dashboard).
- Chaque service n'a accès qu'à **sa** base (cloisonnement `data-net`), avec un compte DB dédié
  à droits limités, pas un superuser partagé.
- Policies Vault par service : un service ne lit que ses propres secrets.

---

## 6. API Gateway — présence, rôle, ce qu'elle protège

### Décision : Traefik **est** l'API Gateway (+ edge + LB + TLS)

La grille demande explicitement : *« Is there an API Gateway to manage incoming requests? »*
et *« APIs only accessible when logged in with an Admin profile »*.

- **Point d'entrée unique.** Toutes les requêtes passent par Traefik. Les services ne sont
  jamais joignables directement de l'extérieur.
- **Rôle** : terminaison TLS, routage par chemin (`/api/identity`, `/api/travels`, `/api/payments`
  → service correspondant), load-balancing sur réplicas, **enforcement d'authentification via
  middleware ForwardAuth** délégué à identity-service.
- **Ce qu'elle protège** : ForwardAuth interroge identity-service à chaque requête entrante ;
  un appel sans JWT valide / sans rôle ADMIN est rejeté **au bord**, avant d'atteindre un service
  métier. C'est le verrou qui satisfait « APIs only accessible with an Admin profile ».
- **Tracing** : Traefik injecte/propage un `X-Request-Id` (corrélation), repris dans les logs
  structurés de chaque service → traçabilité d'une requête à travers les services (voir §7).

### Le point de défaillance unique, assumé : ForwardAuth → identity-service

**Honnêteté requise.** Comme **chaque** requête est authentifiée par un appel ForwardAuth
à identity-service, **identity-service est un point de défaillance unique (SPOF) au
runtime** : s'il tombe, plus aucune requête ne s'authentifie → **toute la stack devient
inaccessible**, y compris travel et payment pourtant up. C'est le prix d'une authN
centralisée et déléguée (la phrase de résilience de §1 est corrigée en conséquence).

- **Conséquence pour la démo failover** (§4) : on tue **travel-service ou payment-service**
  pour montrer le report de trafic — **jamais identity-service**, dont la chute couperait
  toute la stack et ne démontrerait rien d'autre que ce SPOF.
- **Alternative rejetée : validation de signature JWT au bord** (plugin JWT Traefik,
  *stateless*). Traefik vérifierait lui-même la signature du token sans appeler identity à
  chaque requête → **supprime le SPOF runtime**. Rejetée pour un projet **solo et
  timeboxé** : elle coûte un plugin JWT Traefik à configurer/maintenir et impose une
  **révocation différée** (un token reste valide jusqu'à expiration → TTL court
  obligatoire). Plus de pièces mobiles pour un gain qui ne se justifie pas sur une surface
  **admin-only**, où le SPOF est tolérable. À reconsidérer si l'authN devait un jour servir
  un trafic public.

### Tradeoff / alternative rejetée

- **Alternative : Spring Cloud Gateway** (gateway « Spring-native »). Rejetée : +1 JVM (~400 Mo)
  sur 8 Go, et nécessite souvent un registre de services (Eureka/Consul, +1 conteneur) pour
  load-balancer les réplicas. Traefik (binaire Go ~50 Mo) fait gateway + LB + découverte + TLS
  sans registre.
- **Ce que je sacrifie** : l'agrégation de requêtes et la logique métier de gateway côté Spring.
  Inutile ici — un dashboard admin CRUD n'a pas besoin d'agrégation. Si un vrai besoin
  d'aggregation/BFF apparaît, Spring Cloud Gateway pourra s'insérer derrière Traefik.

---

## 7. Logging / tracing (exigence sujet + grille) — **IMPLÉMENTÉ ET VÉRIFIÉ**

> **Statut : ce n'est plus une intention, c'est un mécanisme qui tourne.** Cette section
> décrivait une décision à venir ; elle décrit désormais ce qui est livré et prouvé.
> Détail complet, mesures et procédures : `ansible/roles/observability/README.md`.

- **Exigence** : « track and trace a request across multiple services ».
- **Décision** : logs structurés JSON par service + **corrélation par `X-Request-Id`**
  (porté de service en service), agrégés par **Grafana Loki + Promtail**.
- **Tradeoff / sacrifice** : **ELK écarté** (Elasticsearch seul mange >1 Go sur une machine à 8 Go).
  Loki/Promtail est nettement plus léger. On sacrifie la richesse de recherche d'ELK contre la
  faisabilité RAM. Le critère grille (« tracer une requête à travers les services ») reste rempli
  grâce à la corrélation par ID.

### Ce qui est livré

| Étage | Où |
|---|---|
| **Émission** — JSON sur stdout (`logstash-logback-encoder`), `requestId` en MDC | `services/*/src/main/resources/logback-spring.xml` + `.../filter/RequestIdFilter.java` |
| **Propagation** — en-tête `X-Request-Id` sur l'appel en cascade identity → payment | `PaymentServiceClient` |
| **Collecte / stockage / consultation** | rôle Ansible **`observability`** : Promtail → Loki → Grafana |

Promtail découvre les conteneurs **via l'API Docker** (socket en lecture seule, même
arbitrage de sécurité que Traefik) avec une **liste blanche** sur les conteneurs
applicatifs. **Loki et Promtail ne publient aucun port et ne sont routés par rien** :
l'API de Loki n'a aucune authentification (`auth_enabled: false` n'est pas un contrôle
d'accès mais un séparateur de locataires), ils vivent donc sur un réseau `internal`.
Le seul accès humain aux logs est **Grafana**, derrière Traefik (`grafana.localhost`)
et derrière une authentification.

### Preuve bout-en-bout (résultat réel, pas une intention)

Un `DELETE /users/{id}` déclenchant la cascade identity → payment, émis avec un
`X-Request-Id` connu, puis interrogé en LogQL :

```logql
{job="travel-plan"} | json | requestId=`cascade-…-corr`
```

→ **10 lignes retournées, réparties sur les deux services**, reconstituant la cascade
dans l'ordre : `DELETE /users/…` (identity) → `DELETE /payments/by-user/…` (payment) →
`Completed 200 OK` (payment) → `Completed 204 NO_CONTENT` (identity).

### Deux limites connues, nommées plutôt que masquées

1. **À niveau `INFO`, une requête nominale n'écrit aucune ligne de log.** Le `requestId`
   est bien dans le MDC, mais aucun code applicatif n'émet de log pendant une requête
   réussie — et un MDC sans instruction de log ne produit rien. La corrélation ci-dessus
   n'a été observable qu'avec `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB=DEBUG`. La plomberie
   est démontrée correcte ; ce qui manque, ce sont des **logs métier côté applicatif**.
2. **Les images applicatives doivent être reconstruites.** Toute image antérieure à
   l'ajout de `logback-spring.xml` sort du texte brut : Promtail la collecte, mais
   `| json | requestId=…` ne filtre rien.

### Profil Compose dédié (budget mesuré)

Stack mesuré à **~450 Mo** (Loki 84 Mo + Promtail 31 Mo + Grafana 336 Mo) — trois binaires
Go, **aucune JVM**, ce qui est précisément ce qui le rend finançable. Il a son **propre
profil `observability`**, **additif** : `--profile full --profile observability` ≈ 2,15 Go,
tandis que `full` + `ci` + `observability` ≈ 5,9 Go ne laisserait plus rien à l'IDE.
D'où trois profils composables plutôt qu'un gros profil « tout ».

Le **MVP de repli** (logs JSON + `docker compose logs` filtrés par `X-Request-Id`) reste
valable si la RAM manque le jour de la démo : le profil dédié permet de ne simplement pas
monter le stack.

---

## 8. CI/CD (cadrage Phase 0, implémenté plus tard)

- **Jenkins** : build + tests unitaires par PR. **SonarQube** : qualité de code.
- **Contrainte 8 Go décisive** : Jenkins (~700 Mo) + SonarQube (~1,5 Go avec sa base) **ne
  cohabitent pas** avec la stack applicative complète. **Décision** : la stack CI tourne **à la
  demande** via le **profil `ci`** — le **même mécanisme de profils Compose** qu'en §4 —,
  pas en permanence, et idéalement quand la stack applicative est down.
- **Tradeoff / sacrifice** : pas de CI « toujours allumée ». On sacrifie le confort d'un Jenkins
  permanent ; on lance la stack CI pour valider une PR puis on l'arrête. Assumé sur 8 Go.

---

## 9. Récapitulatif des sacrifices assumés

| Décision | Sacrifié |
|---|---|
| 3 services (fusion auth+user) | Granularité « 1 service / nom », extraction future possible |
| Neo4j pour travel uniquement | Simplicité mono-store ; +1 techno gourmande en RAM |
| Soft-delete + propagation de flag inter-services | Croissance des tables (purge différée) + discipline de filtrage `deleted_at` sur tous les reads ; pas de broker événementiel |
| Index unique partiel (unicité sous soft-delete) | Unicité applicative côté Neo4j ; discipline de schéma (`WHERE deleted_at IS NULL` sur les contraintes Postgres) |
| ForwardAuth centralisé (identity) | `identity-service` = SPOF runtime ; validation JWT *stateless* au bord écartée (projet solo, surface admin-only) |
| Profils Compose (`core` en dev quotidien) | Pas de stack complète testée en continu ; bascule de profil nécessaire |
| ~~Réplicas à 1 par défaut~~ → **2 réplicas par défaut** sur les 3 services applicatifs | Plus le mécanisme lui-même (LB + failover actifs par défaut, prouvés) : restent la non-réplication des **bases** (Postgres/Neo4j singletons = SPOF de données) et le scaling **manuel**, sans autoscaling (relèverait de K8s) |
| Vault dev mode + token bootstrap ansible-vault | Réalisme prod (persistance, auto-unseal, HA) |
| TLS au bord seulement | mTLS interne (mitigé par segmentation réseau) |
| Traefik comme gateway | Logique gateway Spring-native / agrégation BFF |
| Loki au lieu d'ELK | Richesse de recherche des logs |
| CI à la demande | Jenkins/SonarQube permanents |

---

## 10. Points ouverts

**Aucun.** Le seul point qui restait ouvert — **découpage à 3 vs 4 services** (§1) — est
désormais **tranché : 3 services, fusion `identity` confirmée**. Tout le document est
arbitré ; il n'attend que ta validation globale, pas un arbitrage résiduel.

> **STOP — fin de Phase 0.** Je n'écris aucun Dockerfile, playbook ni service tant que tu n'as
> pas validé ce document et l'arborescence ci-dessous.