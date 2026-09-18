# Let's Travel — Décisions d'architecture (Phase 1)

> Document de tranchage, même contrat que `docs/architecture-decisions.md`
> (Phase 0) : chaque décision est **prise**, pas suggérée, et porte son
> tradeoff + ce qu'elle **sacrifie**. Rien n'est codé tant que la phase
> correspondante n'est pas validée.

## Ce qui est hérité de la Phase 0, non renégocié

RAM machine 8 Go, Docker Compose seul (pas de K8s), Spring Boot + Angular,
Vault dev mode, soft-delete partout, 3 services = 3 bounded contexts
(identity/payment/travel), pas de lib Java partagée entre services (chaque
`JwtService`/`TokenValidationService` reste dupliqué par choix), autorisation
**ad hoc** en première ligne de contrôleur (pas de `@PreAuthorize`, pas de
filter chain), Traefik = gateway + LB + TLS + ForwardAuth, propagation
inter-services par flag soft-delete best-effort (pas de saga, pas de broker).
Tout ce qui suit **construit sur** ces choix, ne les rouvre pas.

---

## 1. RBAC réel — de "tout le monde est ADMIN" à 3 rôles

### Constat de code (pas une supposition)

`identity-service` a déjà une claim `role` dans le JWT (`JwtService.CLAIM_ROLE`)
et un mécanisme de rejet (`InsufficientRoleException` → 403), mais `role` est
figé à `"ADMIN"` à la création (`User.java` constructeur, `V3__add_role.sql`
`DEFAULT 'ADMIN'`). Les 3 services ont chacun un check ad hoc
(`requireAdmin`/`requireValidToken`) qui vérifie *une seule* valeur de rôle.

### Décision

- `role` devient un des trois : `ADMIN`, `TRAVEL_MANAGER`, `TRAVELER`, choisi à
  la création du compte (endpoint `POST /users` prend un `role` explicite au
  lieu de le forcer).
- Le check ad hoc existant est **étendu**, pas remplacé : chaque contrôleur
  déclare quels rôles il accepte (`requireAnyRole(header, ADMIN, TRAVEL_MANAGER)`
  par ex.), toujours en première ligne de méthode, toujours sans annotation ni
  filter chain — cohérence avec l'existant plutôt que refonte à mi-projet.
- **Hiérarchie du sujet respectée** : `ADMIN` peut tout ce que peut
  `TRAVEL_MANAGER` et `TRAVELER` ; `TRAVEL_MANAGER` peut tout ce que peut
  `TRAVELER`. Implémentation : `requireAnyRole` accepte une liste, et `ADMIN`
  est ajouté implicitement à toute liste (un helper `impliedRoles(role)` plutôt
  que de lister `ADMIN` à chaque appel).
- **Ownership-aware** : au-delà du rôle, certains endpoints doivent vérifier
  que la ressource appartient à l'appelant (un `TRAVELER` ne voit que ses
  propres abonnements/paiements/feedback ; un `TRAVEL_MANAGER` ne gère que ses
  propres travels). Comparaison `userId` du JWT vs `ownerId`/`managerId` de la
  ressource, dans le service, pas dans un filtre générique.

### Ce que je sacrifie

Pas de RBAC déclaratif centralisé (annotations, policy engine) : la
vérification reste dispersée, une ligne par méthode, dans chaque service.
C'est plus verbeux, mais c'est le seul choix cohérent avec le pattern déjà en
place dans les 3 services — introduire un deuxième mécanisme au milieu du
projet coûterait plus cher en incohérence qu'il ne rapporte en élégance.

### Alternative rejetée

Spring Security `@PreAuthorize` + filter chain. Rejetée : aucun des 3 services
n'a de `SecurityFilterChain` aujourd'hui (`SecurityConfig` s'en passe
explicitement, Spring Security n'y sert qu'à `BCryptPasswordEncoder`) ;
l'introduire maintenant reviendrait à réécrire l'autorisation existante en
double, pour un gain marginal sur un projet où chaque contrôleur a déjà son
check explicite et testé.

---

## 2. Domaine `Travel` (Neo4j, travel-service)

### Décision

Nouveau nœud Neo4j `Travel` : `id`, `title`, `startDate`, `endDate`,
`durationDays`, `price`, `capacity`, `managerId` (référence applicative vers
l'`id` d'un `User` `TRAVEL_MANAGER` côté identity-service — pas de FK
cross-store, même principe que payment-service aujourd'hui), `deletedAt`.
Relations : `(Travel)-[:HAS_DESTINATION]->(Destination)` (les `Destination`
existantes sont réutilisées, pas dupliquées), `(Travel)-[:HAS_ACTIVITY]->(Activity)`,
`(Travel)-[:HAS_ACCOMMODATION]->(Accommodation)`. Les entités `Activity` et
`Accommodation` existent déjà dans le code (`entity/Activity.java`,
`entity/Accommodation.java`) mais ne sont rattachées à rien : elles deviennent
les enfants de `Travel`.

CRUD `Travel` suit exactement le pattern de `DestinationService`/
`DestinationController` : SDN repository pour le nœud simple, `Neo4jClient` +
Cypher explicite pour les relations `HAS_*` (même raison que
`TransportRepository` : SDN réécrirait toute la collection de relations à
chaque save).

### Ce que je sacrifie

`Travel` reste dans travel-service/Neo4j (pas une nouvelle base dédiée) : ça
concentre encore plus de responsabilités structurelles (CRUD classique, pas
seulement du graphe de transport) sur le store le plus RAM-coûteux de la
stack (§4 Phase 0). Cohérent avec la décision Phase 0 §2 ("Neo4j pour
travel-service, dette consciente") plutôt que de la rouvrir.

### Alternative rejetée

Stocker `Travel` en PostgreSQL (nouveau service ou table dans travel-service)
et ne garder Neo4j que pour `Destination`/`Transport`. Rejetée : ça
fragmenterait le bounded context "itinéraires" en deux stores pour une seule
frontière métier, et casserait la lecture naturelle du graphe nécessaire aux
recommandations (§7) — `Travel` doit être un nœud du même graphe que les
relations `SUBSCRIBED`/`GAVE_FEEDBACK` pour que les requêtes de
recommandation restent un seul parcours Cypher.

### Correctif — ce que le code dit vraiment (relu avant d'implémenter §2)

Le texte ci-dessus a été rédigé sur la foi d'un `services/README.md` obsolète
("Non implémenté : pas de `Travel`, `Activity`, `Accommodation`"). En relisant
le code de travel-service avant de coder cette phase, ce n'est plus vrai :
`Destination` a déjà `startDate`/`endDate`/`durationDays` (dérivé), et possède
déjà `Activity` et `Accommodation` via `HAS_ACTIVITY`/`HAS_ACCOMMODATION`
(voir `CreateDestinationRequest.java`, `Destination.java`, `Activity.java`,
`Accommodation.java`, `ActivityRepository.java`, `AccommodationRepository.java`).
Il ne manque, par rapport à ce que le sujet exige d'un "Travel" (destination(s),
dates, durée, activités, hébergement, transport, manager propriétaire,
capacité/prix pour permettre l'abonnement), que trois champs : `managerId`,
`price`, `capacity`. Le reste — dates, durée dérivée, activités, hébergements,
et même le transport inter-étapes via `TRANSPORT` — existe déjà sur
`Destination`.

**Décision révisée : option (a), pas (b).** `Destination` est étendue avec
`managerId` (UUID, référence applicative vers un `User` `TRAVEL_MANAGER`
côté identity-service — même principe que `userId` sur `Payment`, pas de FK
cross-store), `price` (`BigDecimal`, >= 0) et `capacity` (`Integer`, >= 1).
Elle devient de fait l'entité "Travel" du sujet — **sans renommer** la classe
Java ni le label Neo4j : `Destination` reste `Destination`, seul son rôle
métier s'élargit. La relation `TRANSPORT` entre deux `Destination` reste
inchangée (elle modélise déjà le trajet entre étapes d'un même voyage, exactement
ce qu'un `Travel` multi-destinations demande). CRUD suit le pattern déjà en
place : `Neo4jRepository` pour le nœud simple (`managerId`/`price`/`capacity`
sont de simples propriétés scalaires, `RETURN d` les rapatrie sans rien
changer aux requêtes existantes), `Neo4jClient` + Cypher explicite déjà en
place pour `HAS_ACTIVITY`/`HAS_ACCOMMODATION`/`TRANSPORT` inchangé.

**Ownership** : `TRAVEL_MANAGER` ne peut créer un voyage qu'en son propre nom
(`managerId` du body doit être égal au `sub` du token, sinon 403 — vérifié en
première ligne de contrôleur, même pattern que
`PaymentController.create`/`TokenValidationService.requireOwnerOrAdmin` côté
payment-service) et ne peut modifier/supprimer que les voyages dont il est le
`managerId` (`ADMIN` outrepasse, oversight complet exigé par le sujet). Les
lectures (`GET`) restent ouvertes à tout rôle connu, inchangé depuis §1 — le
catalogue est public, seule la gestion est ownership-aware.

### Ce que je sacrifie (mise à jour)

Pas de nouveau nœud `Travel` ni de relation `HAS_DESTINATION` : un
`TRAVEL_MANAGER` qui veut proposer un voyage sur plusieurs destinations doit
créer plusieurs `Destination` (une par étape) reliées par `TRANSPORT`, il n'y
a pas de nœud "voyage" englobant distinct des étapes elles-mêmes — une
`Destination` avec `managerId`/`price`/`capacity` est en elle-même
"réservable". C'est un choix délibérément plus simple que (b) : dupliquer un
nœud `Travel` autour de `Destination` pour porter les trois champs
manquants aurait recréé, avec une relation en plus à maintenir sous
soft-delete sur chaque hop, exactement les champs que `Destination` peut
porter nativement. Reconsidérer uniquement si un besoin réel de "voyage
multi-destinations vendu comme un seul package avec un prix/capacité
communs" apparaît plus tard — non demandé explicitement par le sujet, qui
parle de "travel" au singulier avec "destination(s)" comme un de ses
attributs, pas comme une collection de sous-voyages indépendants.

### Alternative rejetée (mise à jour)

Introduire un nœud `Travel` distinct wrappant une ou plusieurs `Destination`
via `HAS_DESTINATION` (idée initiale de ce document, §2 ci-dessus). Rejetée
maintenant que le code réel est relu : `Destination` porte déjà tout ce qui
justifierait normalement un nœud séparé (dates, durée, activités,
hébergements, transport). Ajouter `Travel` par-dessus aurait été de la
duplication pure — un nœud supplémentaire, une relation `HAS_DESTINATION` à
maintenir sous soft-delete sur deux hops, pour ne porter au final que trois
propriétés scalaires (`managerId`/`price`/`capacity`) que `Destination`
peut porter elle-même. Va à l'encontre de la culture du repo (Phase 0,
`docs/architecture-decisions.md`) qui rejette systématiquement la complexité
non justifiée par un besoin réel.

---

## 3. Abonnements (subscribe/unsubscribe, cutoff 3 jours)

### Décision

Relation Neo4j `(Traveler)-[:SUBSCRIBED {status, subscribedAt, cancelledAt}]->(Travel)`.
`Traveler` n'est **pas** un nœud dupliquant `User` — c'est une référence
applicative : la relation part d'un nœud léger `TravelerRef {userId}` créé à
la volée (pattern `MERGE`), pour que Cypher puisse relier un `userId` externe
sans jamais recopier les données d'identity-service. `status` ∈
`ACTIVE | CANCELLED`. Annulation : le service vérifie
`travel.startDate - now() >= 3 jours` avant d'autoriser `status → CANCELLED` ;
sinon 409.

### Ce que je sacrifie

Pas de table Postgres dédiée aux abonnements : l'intégrité reste applicative
(pas de contrainte d'unicité native Traveler×Travel côté Neo4j Community — à
gérer en code, même piège que documenté en Phase 0 §3 pour l'unicité sous
soft-delete). En échange, l'historique de participation est immédiatement
exploitable par les recommandations (§7) sans jointure cross-store.

### Alternative rejetée

Table `subscriptions` dans payment-service ou identity-service (relationnel,
intégrité native). Rejetée : la relation `SUBSCRIBED` est la donnée
d'entrée principale des recommandations Neo4j exigées par le sujet — la sortir
du graphe obligerait la fonctionnalité qui justifie Neo4j (§7) à faire des
appels cross-service à chaque calcul de recommandation.

### Correctif — terminologie et détail d'implémentation (relu avant d'implémenter §3)

Le texte ci-dessus parle de `Travel` comme un nœud dédié ; suite au correctif
de §2 (relu avant §3, "option (a), pas (b)"), il n'y a pas de nœud `Travel` —
c'est `Destination` qui porte ce rôle. Partout ci-dessus, lire `Travel` comme
`Destination` : la relation implémentée est
`(TravelerRef {userId})-[:SUBSCRIBED {status, subscribedAt, cancelledAt}]->(Destination)`,
et le cutoff vérifie `destination.startDate - now() >= 3 jours`. Rien
d'autre ne change : le raisonnement ("pourquoi Neo4j plutôt qu'une table
Postgres", "pourquoi pas de contrainte d'unicité native") s'applique à
l'identique à `Destination`.

Trois décisions supplémentaires prises pendant l'implémentation, pas
couvertes par le texte initial :

- **Abonnement dupliqué** (`POST` alors qu'un abonnement `ACTIVE` existe déjà
  pour la même paire traveler/destination) → **409**, pas un no-op silencieux
  ni un 200. Chaque `subscribe` crée une nouvelle relation `SUBSCRIBED`
  plutôt que de réutiliser/réinitialiser une relation existante — nécessaire
  pour que le compteur "annulations d'abonnement" de la page de stats
  personnelles du traveler (sujet) reste exact dans le temps. Réussir
  silencieusement sur un doublon créerait donc une seconde relation active
  redondante plutôt qu'un simple no-op ; un 409 est la seule réponse honnête.
- **Abonnement à une destination dont `startDate` est déjà passée** → 409
  également : la destination existe et est valide, il n'y a simplement plus
  rien à rejoindre. Un 400 aurait été défendable (requête malformée dans
  l'absolu) mais la ressource elle-même est valide ; c'est son état temporel
  qui rend l'action impossible — même catégorie que le cutoff ci-dessous,
  donc même code HTTP pour rester cohérent.
- **`DELETE /destinations/{id}/subscriptions/{travelerId}`** (désabonnement
  forcé par le manager/admin, explicitement demandé par le sujet :
  "options to ... unsubscribe travelers from the travel") **n'applique pas**
  le cutoff de 3 jours qui protège le désabonnement en libre-service du
  traveler. Un manager qui retire un traveler proche du départ est une
  décision administrative/de capacité (no-show, violation de politique), pas
  le cas de flexibilité que le cutoff protège — le bloquer aurait retiré un
  outil que le sujet demande explicitement aux managers.

---

## 4. Paiement d'un abonnement (payment-service)

### Constat de code

`Payment` (payment-service) a déjà `provider` (`MANUAL/STRIPE/PAYPAL`),
`externalReference`, et un flux `PENDING → COMPLETED|FAILED` immuable une fois
terminal. Stripe (PaymentIntent + webhook HMAC vérifié) et PayPal
(create+capture, pas de webhook) sont réellement câblés. Rien ne relie un
`Payment` à un abonnement/travel aujourd'hui.

### Décision

`Payment` gagne deux colonnes optionnelles : `travelId`, `subscriptionRef`
(l'identifiant applicatif de la relation `SUBSCRIBED`, pas une FK). Flux :
travel-service crée la relation `SUBSCRIBED{status: PENDING_PAYMENT}`, appelle
`payment-service` (`POST /payments` avec provider au choix du traveler) via un
client dédié — même pattern que `PaymentServiceClient` côté identity (token de
service, timeout court, échec loggé et avalé). Au retour `COMPLETED`,
travel-service repasse la relation à `ACTIVE` ; au retour `FAILED`, elle
repasse à `CANCELLED`. Le webhook Stripe (asynchrone) doit donc aussi notifier
travel-service — même appel HTTP interne, dans l'autre sens.

### Ce que je sacrifie

Toujours pas de transaction distribuée : une fenêtre existe où le paiement est
`COMPLETED` côté payment-service mais où l'appel de confirmation vers
travel-service échoue (réseau). Traité comme le reste du repo : réconciliation
best-effort (job de rattrapage possible plus tard), pas une nouvelle
exigence introduite pour ce flux spécifiquement.

### Alternative rejetée

Faire porter le statut de paiement uniquement par polling côté dashboard
(travel-service ne serait jamais notifié). Rejetée : ça laisserait
`SUBSCRIBED` bloqué en `PENDING_PAYMENT` indéfiniment sur un paiement asynchrone
(Stripe webhook), ce qui casserait le cutoff de 3 jours (impossible de savoir
si l'abonnement est réellement actif).

---

## 5. Feedback & signalements

### Décision

- **Feedback** (note + commentaire sur un travel participé) :
  `(Traveler)-[:GAVE_FEEDBACK {rating, comment, createdAt}]->(Travel)`, même
  raison que §3 — c'est un des trois signaux d'entrée de la recommandation
  (§7) et de la note de performance des managers (dashboard Admin).
- **Signalements** (un traveler signale un travel manager ou un autre
  traveler) : table PostgreSQL `reports` dans **identity-service**
  (`reporterId`, `reportedUserId`, `reason`, `status`, `createdAt`,
  `deletedAt`). C'est une notion de modération de comptes, ACID par nature
  (un admin doit pouvoir changer un statut de façon transactionnelle et
  fiable), pas une notion de graphe.

### Ce que je sacrifie

Deux notions proches (avis sur une expérience vs signalement d'un compte)
finissent dans deux stores différents. Choix justifié par la nature de chaque
donnée (traversable/pondérable pour le feedback, transactionnelle/administrative
pour le signalement), pas par confort — comme la répartition Postgres/Neo4j
déjà actée en Phase 0 §2.

### Alternative rejetée

Tout mettre en Neo4j pour "un seul endroit où chercher la réputation d'un
utilisateur". Rejetée : les signalements sont un flux de modération avec des
transitions de statut (`OPEN → REVIEWED → DISMISSED/ACTIONED`) qu'un admin
doit pouvoir auditer de façon fiable — le confort d'un store unique ne
vaut pas de perdre les garanties ACID sur cette donnée sensible.

### 5bis. `POST /reports` — faut-il vérifier l'existence de `reportedUserId` ?

#### Décision

`POST /reports` vérifie que `reportedUserId` correspond à un utilisateur actif
(non soft-delete) dans `identity-service` avant de créer le signalement — 404
(`UserNotFoundException`, même exception que `GET /users/{id}`) sinon.

#### Justification

Ce n'est **pas** le même arbitrage que `Payment.userId` côté payment-service
(§4 constat de code : payment-service n'a aucun accès à `identity_db`, donc
son `userId` reste une référence applicative non vérifiée, un choix déjà acté
en Phase 0). Ici, le contrôleur qui reçoit `reportedUserId` tourne **dans le
même service, sur la même base** que la table `users` — la vérification est
une requête `UserRepository.findActiveById` déjà existante, pas un appel
cross-service, pas de coût architectural. Refuser de vérifier une donnée que
le service peut vérifier gratuitement affaiblirait la table `reports` sans
aucune contrepartie : n'importe quel UUID syntaxiquement valide (y compris un
id d'un autre domaine, ou un id jamais attribué) créerait un signalement
orphelin qu'un admin ne pourrait jamais rattacher à un compte réel dans
`GET /reports`.

#### Ce qui est sacrifié

Le sujet dit "signale un Travel Manager ou un autre traveler" — la vérification
ne distingue donc pas le rôle de `reportedUserId` (n'importe quel utilisateur
actif, `ADMIN` inclus, peut techniquement être signalé) : ajouter une
contrainte de rôle sur la cible serait une règle métier que le sujet ne
demande pas explicitement et compliquerait la vérification sans bénéfice
clair pour l'audit.

#### Alternative rejetée

Ne pas vérifier `reportedUserId` du tout (accepter tout UUID, symétrique à
`Payment.userId`). Rejetée : cette justification ne s'applique que quand la
vérification est impossible ou coûteuse (cross-service) — ici elle est
gratuite, donc ne pas vérifier serait un choix par confort, pas par nécessité
architecturale, ce qui n'est pas le standard que ce document applique ailleurs
(voir §4, §7 : les sacrifices sont toujours justifiés par une contrainte
réelle, jamais par la paresse).

---

## 6. Recherche Elasticsearch + autocomplete

### Décision

Elasticsearch **intégré à travel-service**, pas un 4ᵉ microservice Spring :
un composant de synchronisation applicative pousse chaque `Travel` (avec ses
destinations/activités agrégées) vers un index `travels` à la création, la
mise à jour et le soft-delete (retrait de l'index, pas suppression physique
du document — miroir du soft-delete). Endpoints exposés par travel-service :
`GET /travels/search?q=...` (recherche plein texte multi-champs) et
`GET /travels/autocomplete?prefix=...` (suggester ES, pas une requête `LIKE`
Postgres/Cypher).

### Rebudget RAM (§4 Phase 0 mis à jour)

Le budget Phase 0 (~3,8 Go au repos, ~6,5-7 Go en dev) **n'avait pas
Elasticsearch**. Une instance ES mono-nœud dev coûte facilement 512 Mo-1 Go de
heap minimum + overhead JVM — comparable à Neo4j. Décision : **profil Compose
dédié `search`** (additif, comme `observability`), pour ne jamais l'imposer en
mode dev quotidien (`core`). Mesure réelle à faire dès que `search` tourne,
même discipline que Phase 0 ("dette de test : ce budget est estimé, pas
mesuré").

### Ce que je sacrifie

Pas de CDC/Kafka pour la synchronisation Neo4j → ES : c'est un dual-write
applicatif synchrone (même esprit que la propagation de flag inter-services
déjà actée). Une écriture Neo4j réussie suivie d'un échec d'indexation ES
laisse l'index légèrement en retard — traité par une ré-indexation à la
demande (endpoint admin ou job), pas par une garantie de cohérence forte.
C'est le seul choix qui tienne dans le budget RAM sans ajouter un broker.

### Alternative rejetée

Un `search-service` Spring Boot séparé consommant Neo4j. Rejetée : +1 JVM
Spring (~550 Mo, cf. §4 Phase 0) pour une fonctionnalité qui n'a besoin
d'aucune logique métier propre — juste d'indexer et interroger. Le sacrifice
(un composant de plus dans travel-service au lieu d'un service séparé) est
moins coûteux que le sacrifice RAM.

### Correctif — implémentation réelle (relu avant de coder cette section)

Le texte ci-dessus parlait de `Travel` avant que §2 ne soit corrigé : il n'y a
pas de nœud `Travel` séparé, l'entité indexée est `Destination` (qui porte
déjà `managerId`/`price`/`capacity` en plus de ses dates/activités/
hébergements, cf. §2). Partout où ce document dit « Travel » ci-dessus, lire
« Destination ». Détails de l'implémentation réelle, non anticipés à l'écriture
de la décision ci-dessus :

- **Index** `destinations` (pas `travels`), un document par `Destination`
  active. Champs indexés, conformes à « across all travel details » du
  sujet : `name`, `country`, `activities` (noms, tableau), `accommodations`
  (noms, tableau), `price`, `capacity`, `startDate`, `endDate`, `managerId`.
  Un champ `suggest` de type `completion` (input = `name` + `country`) porte
  l'autocomplete — un vrai suggester ES, pas un `LIKE` Postgres/Cypher.
- **Sync** : dual-write synchrone confirmé, déclenché depuis
  `DestinationService.create`/`update` (indexation/mise à jour du document)
  et `DestinationService.delete` (suppression du document, pas un flag) —
  un échec d'indexation est **loggé et avalé**, jamais renvoyé à l'appelant
  (même philosophie que `PaymentServiceClient` : l'écriture Neo4j, source de
  vérité, ne doit jamais échouer à cause d'un problème sur l'index
  secondaire). Ré-indexation à la demande non implémentée dans cet
  incrément (pas d'endpoint admin de ré-indexation) — dette assumée,
  cohérente avec le "pas de garantie de cohérence forte" déjà écrit ci-dessus.
- **Suppression du document au lieu d'un flag `inactive`** (le texte
  ci-dessus laissait les deux options ouvertes — « retrait de l'index, pas
  suppression physique du document ») : décision tranchée en faveur de la
  suppression réelle du document ES. Justification : contrairement à Neo4j
  (où le soft-delete protège contre la perte de relations et permet un
  historique), un document ES n'est qu'une projection dérivée, jamais la
  source de vérité — le supprimer ne perd aucune donnée (elle reste dans
  Neo4j) et garantit mécaniquement qu'un destination soft-deleted ne peut
  plus jamais apparaître dans une recherche, sans dépendre d'un filtre
  applicatif supplémentaire au moment de la requête.
- **Client Java** : `RestClient` bas niveau (`org.elasticsearch.client:
  elasticsearch-rest-client`) avec des requêtes JSON brutes (Jackson pour
  sérialiser/désérialiser), plutôt que le client typé `co.elastic.clients:
  elasticsearch-java` ou Spring Data Elasticsearch. Sacrifice : pas de DSL
  Java typé pour construire les requêtes ES (JSON en chaînes, moins de
  sécurité à la compilation). Bénéfice : cohérent avec la culture du repo qui
  préfère déjà le Cypher explicite à l'ORM magique dès que la traduction
  objet↔requête devient non triviale (`Neo4jClient` pour `TRANSPORT`/
  `HAS_ACTIVITY`, cf. §2 Phase 0) — Spring Data Elasticsearch apporterait une
  couche d'auto-configuration et de mapping annotation-driven pour deux
  endpoints et un seul index, `elasticsearch-java` apporterait un gros
  générateur de code pour le même besoin restreint. `RestClient` est la plus
  petite dépendance qui fait le travail, auditable en JSON brut.
- **Profil Compose `search`** livré comme un rôle Ansible minimal
  (`ansible/roles/elasticsearch/`), même structure que le rôle `neo4j`
  (fragment Compose rendu, aucun provisioning applicatif). Écart assumé par
  rapport aux autres rôles du projet : **pas de suite molecule** pour ce
  rôle — c'est un unique conteneur dev-mode qui ne fait que rendre un
  fragment Compose statique (même geste que `neo4j`), la charge de test
  molecule n'apporterait rien qu'un `docker compose --profile search up`
  manuel ne vérifie pas déjà pour ce périmètre. À reconsidérer si le rôle
  gagne en complexité (TLS, plusieurs nœuds, provisioning).

---

## 7. Recommandations personnalisées (Neo4j)

### Décision

Requête Cypher combinant **au moins 3 champs** du travel (le sujet l'exige
explicitement) — proposés : `destination(s)` (via `HAS_DESTINATION`), un champ
`activityType` sur `Activity`, et une fourchette de `price` — pondérés par
l'historique du traveler : `SUBSCRIBED` passés (participation) et
`GAVE_FEEDBACK` (rating). Approche : similarité de contenu (travels partageant
des destinations/activités/gamme de prix avec ceux que le traveler a aimés ou
suivis), pas de filtrage collaboratif utilisateur×utilisateur — plus simple à
justifier et à expliquer à l'audit ("comprehension" de la grille) qu'un modèle
de type matrice de similarité.

### Ce que je sacrifie

Pas de moteur de recommandation ML (pas d'embeddings, pas de bibliothèque de
scoring). Une requête Cypher explicite et lisible plutôt qu'une boîte noire —
assumé comme suffisant pour la grille ("can Neo4j deliver precise travel
suggestions"), qui évalue la pertinence perçue, pas la sophistication de
l'algorithme.

### Alternative rejetée

Filtrage collaboratif (similarité entre travelers). Rejetée : nécessite un
volume de données que ce projet n'aura jamais en démo, et complique la
justification orale demandée par l'audit ("ask the students to elaborate") —
une requête de contenu à 3 champs pondérés est plus simple à défendre et à
tester avec 2 comptes de démo aux profils différents.

---

## 8. Dashboards par rôle (admin-dashboard, Angular)

### Constat de code

`AppShellComponent` a 3 liens codés en dur, `authGuard` ne vérifie que la
présence d'un token (aucune notion de rôle, commentaire explicite dans le
fichier), `AuthService` ne décode que `sub` du JWT, aucun bouton logout n'est
câblé. `shared/ui/` (alert/button/card/input) et le pattern
`features/<nom>/{*.component,*.service}` sont directement réutilisables.

### Décision

- `AuthService` décode aussi la claim `role` du JWT et l'expose en signal.
- `authGuard` devient `roleGuard(...roles)` : vérifie token + rôle (avec la
  même hiérarchie implicite que §1 — `ADMIN` passe partout).
- `AppShellComponent` devient data-driven : une liste de nav items
  `{label, route, roles}` filtrée par le rôle courant, au lieu du template à 3
  liens en dur. Le bouton logout (déjà dans `AuthService.logout()`, jamais
  câblé) est ajouté au shell à cette occasion.
- Nouveaux écrans par rôle sous `features/` en suivant le pattern existant :
  `admin/` (top managers/travels, revenus, historique+feedbacks, classement
  managers, file de signalements), `manager/` (stats travels, abonnés,
  analytics), `traveler/` (recherche+autocomplete, recommandations,
  abonnement, paiement, feedback, page manager, signalement, stats
  personnelles).

### Ce que je sacrifie

Refonte du shell de navigation (actuellement 3 liens en dur) — pas de
contournement propre possible si le sujet impose 3 expériences différentes.
C'est le seul point de cette phase qui touche un composant partagé existant
plutôt que d'en ajouter un nouveau.

---

## 9. Tests & CI

Pas de nouvelle décision : extension du pattern déjà acté en Phase 0.
Testcontainers pour chaque nouvel endpoint backend, Playwright pour chaque
nouveau parcours cross-écran (même esprit que les 4 specs `e2e/` existantes),
Vitest pour la logique front pure (guards, services). Jenkins/SonarQube
étendus aux nouveaux modules mais toujours **à la demande** (profil `ci`,
contrainte RAM Phase 0 §8 inchangée — pas de CI permanente).

## 10. Sécurité — vérification, pas nouvelle décision

Ce que l'audit demande de prouver est déjà en place structurellement : JPA
paramétré (pas de concat SQL → protection injection native), BCrypt sur les
mots de passe, TLS terminé par Traefik. Reste à **vérifier explicitement**
(pas à construire) : aucun binding Angular non sanitizé (`[innerHTML]` sur du
contenu utilisateur type feedback/commentaire de signalement — à auditer
puisque cette phase introduit du texte libre saisi par les travelers) et
qu'aucun nouvel endpoint ne réintroduit une requête Cypher/SQL concatenée
à partir d'un paramètre utilisateur. À faire via le skill `/security-review`
une fois chaque phase codée, pas en amont.

---

## Récapitulatif des sacrifices de cette phase

| Décision | Sacrifié |
|---|---|
| RBAC ad hoc étendu (pas d'annotation) | Verbeux par endpoint, cohérent avec l'existant |
| `Travel` dans Neo4j (pas un nouveau store) | Concentre encore plus de charge structurelle sur le store le plus lourd en RAM |
| Abonnements/feedback en relations Neo4j | Intégrité applicative uniquement ; mais alimentent directement les recommandations |
| Signalements en Postgres (identity-service) | Deux stores pour deux notions voisines — justifié par la nature ACID vs graphe |
| Elasticsearch intégré à travel-service, profil `search` additif | Pas de CDC ; dual-write applicatif, ré-indexation à la demande en cas de dérive |
| Recommandation par requête de contenu (3 champs pondérés) | Pas de ML/filtrage collaboratif |
| Refonte du shell Angular (nav data-driven) | Seul composant partagé existant touché par cette phase |
| Paiement d'abonnement best-effort (pas de transaction distribuée) | Fenêtre d'incohérence transitoire possible, réconciliée comme le reste du repo |

## Points ouverts

**Un seul, volontairement laissé ouvert** : la feature bonus "innovante" du
sujet (§ Bonus). Pas d'arbitrage par défaut ici — c'est la seule partie
créative de cette phase, à discuter avec l'utilisateur au moment voulu plutôt
que figée à l'avance. PWA (`ng add @angular/pwa`) et i18n (`@angular/localize`)
n'ont pas besoin de ce débat : ce sont des ajouts mécaniques, à faire en fin de
phase une fois le cœur fonctionnel validé.

> **STOP — chaque section ci-dessus est une phase séparée.** Ne pas
> implémenter tout ce document d'un coup : `/adr` sert à confirmer le détail
> d'une phase précise juste avant de la coder, dans l'ordre 1 → 10 (les
> dépendances vont dans ce sens — RBAC avant ownership, `Travel` avant
> abonnements/feedback/recherche/recommandations qui en dépendent tous).
