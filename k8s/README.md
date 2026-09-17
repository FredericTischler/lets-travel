# Kubernetes — démonstration du bonus

Ce répertoire répond au **bonus** du sujet (`docs/sujet.md`, section Bonus) :

> *Incorporate Kubernetes alongside Ansible to enhance service management,
> orchestration, and load-balancing capabilities.*

Le mot important est **alongside**, pas *instead of*.

## Ce que ce répertoire est — et n'est pas

**Le déploiement du projet reste Ansible + Docker Compose.** C'est lui qui est
provisionné, testé (Molecule), branché sur Vault et sur la CI Jenkins. Rien
ici ne le remplace, et aucun fichier hors de `k8s/` n'a été modifié pour
l'ajouter.

Ces manifestes sont une **transposition démonstrative** de la même topologie
sur Kubernetes : mêmes images, mêmes tags, mêmes noms de services, mêmes
variables d'environnement, mêmes hostnames de routage. L'objectif est de
montrer que l'architecture tient sur un orchestrateur, et d'expliciter *où*
les deux modèles divergent — c'est là que se trouve l'intérêt pédagogique
(voir « Différences conceptuelles » plus bas).

## Contenu

| Fichier | Objets |
|---|---|
| `00-namespace.yaml` | Namespace `travel-plan` |
| `10-configmap.yaml` | ConfigMap `travel-plan-config` (config **non sensible** partagée) |
| `20-postgres.yaml` | Service headless + ConfigMap d'init + StatefulSet + PVC 2 Gi |
| `21-neo4j.yaml` | Service headless + StatefulSet + PVC 2 Gi |
| `22-vault.yaml` | Service headless + StatefulSet (dev mode, **sans PVC**, cf. en-tête du fichier) |
| `30-identity-service.yaml` | Service ClusterIP + Deployment **2 replicas** |
| `31-payment-service.yaml` | Service ClusterIP + Deployment **2 replicas** |
| `32-travel-service.yaml` | Service ClusterIP + Deployment **2 replicas** |
| `40-ingress.yaml` | Ingress, routage par host (`*.localhost`) |
| `create-secrets.sh` | Crée le Secret `travel-plan-secrets` **depuis l'environnement** |

Le préfixe numérique donne l'ordre de lecture, pas un ordre d'application :
Kubernetes est déclaratif, `kubectl apply -f k8s/` applique le tout et chaque
objet converge indépendamment. L'ordre de démarrage réel est assuré par les
initContainers (voir plus bas), pas par l'ordre des fichiers.

`kubectl apply -f k8s/` ignore `create-secrets.sh` : un répertoire n'est lu que
pour ses `.yaml` / `.yml` / `.json`. Le script est donc sans danger à cet endroit.

## Prérequis

### 1. Un cluster

Testé sur **kind v1.31.0** (nœud unique). Fonctionne aussi sur minikube ou
Docker Desktop. Pour reproduire exactement l'environnement de test :

```bash
kind create cluster --name travel-plan --config - <<'EOF'
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    # Requis pour qu'ingress-nginx puisse se placer sur ce nœud.
    kubeadmConfigPatches:
      - |
        kind: InitConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    # Publie les ports du controleur Ingress sur l'hote. 8081/8443 et non
    # 80/443 : sur la machine de developpement du projet, Traefik (deploiement
    # Compose) occupe deja 443, et 80 est frequemment pris. Adapter au besoin —
    # les commandes de test plus bas utilisent 8081.
    extraPortMappings:
      - containerPort: 80
        hostPort: 8081
        protocol: TCP
      - containerPort: 443
        hostPort: 8443
        protocol: TCP
EOF
```

#### Cluster déjà créé, mais ni binaire `kind` ni kubeconfig

Cas rencontré en pratique : le conteneur `travel-plan-control-plane` tourne
toujours, mais `kind` n'est pas (ou plus) installé et `~/.kube/config` est
absent — typiquement après un changement de poste ou un nettoyage de `~`.
`kubectl` retombe alors sur son défaut `http://localhost:8080` et renvoie une
erreur trompeuse : sur cette machine, 8080 est **Jenkins**, d'où un
`Authentication required` en HTML au lieu d'une erreur de connexion.

Le kubeconfig se reconstruit depuis le nœud, sans `kind` et sans recréer le
cluster (ce qui détruirait les volumes) :

```bash
# Port hôte sur lequel kind a publié l'API server (aléatoire, sur 127.0.0.1)
docker port travel-plan-control-plane 6443     # -> 127.0.0.1:<PORT>

mkdir -p ~/.kube
docker exec travel-plan-control-plane cat /etc/kubernetes/admin.conf > ~/.kube/config
chmod 600 ~/.kube/config

# admin.conf pointe vers https://travel-plan-control-plane:6443, un nom qui ne
# resout que DANS le reseau Docker. Depuis l'hote, viser le port publie.
kubectl config set-cluster travel-plan --server=https://127.0.0.1:<PORT>
kubectl config rename-context kubernetes-admin@travel-plan kind-travel-plan

kubectl get nodes    # doit afficher travel-plan-control-plane Ready
```

Le certificat de l'API server porte `127.0.0.1` dans ses SAN (kind l'ajoute à la
création) : aucune option `--insecure-skip-tls-verify` ni `tls-server-name`
n'est nécessaire, et il ne faut pas en ajouter.

### 2. Un Ingress Controller

`40-ingress.yaml` est une **déclaration**, pas une implémentation. Sans
contrôleur, l'objet est créé et silencieusement ignoré — aucune erreur, aucun
effet. C'est le piège classique.

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.2/deploy/static/provider/kind/deploy.yaml
kubectl -n ingress-nginx wait --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller --timeout=180s
```

### 3. Les images applicatives, chargées à la main

**Il n'y a pas de registry configuré dans ce projet.** Les images
`travel-plan/*:0.1.0` sont construites localement par les Dockerfiles des
services et n'existent sur aucun registry public. Le kubelet ne peut donc pas
les *pull* : elles doivent être injectées dans le nœud.

```bash
# Construction (si pas deja fait par le deploiement Compose ou la CI)
docker build -t travel-plan/identity-service:0.1.0 services/identity-service
docker build -t travel-plan/payment-service:0.1.0  services/payment-service
docker build -t travel-plan/travel-service:0.1.0   services/travel-service

# Injection dans le noeud kind
for s in identity payment travel; do
  kind load docker-image "travel-plan/${s}-service:0.1.0" --name travel-plan
done
```

Sans le binaire `kind` sous la main, l'équivalent direct via containerd :

```bash
docker save travel-plan/travel-service:0.1.0 \
  | docker exec -i travel-plan-control-plane ctr -n k8s.io images import --all-platforms -
```

C'est cette seconde forme qui a servi lors du test décrit plus bas. À noter :
containerd enregistre l'image sous `docker.io/travel-plan/travel-service:0.1.0`,
ce qui est bien la résolution canonique du tag court écrit dans les manifestes.

Pour savoir si l'injection a déjà été faite — et si l'image du nœud est bien la
build courante de l'hôte, pas une plus ancienne — comparer les IDs :

```bash
docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' | grep travel-plan
docker exec travel-plan-control-plane crictl images | grep -E 'travel-plan|postgres|neo4j|vault'
```

Les douze premiers caractères de l'ID doivent coïncider. S'ils diffèrent, le nœud
sert une image périmée : recharger. Les images de base (`postgres`, `neo4j`,
`vault`) gagnent à être injectées de la même façon plutôt que *pull* depuis
Docker Hub — elles sont déjà sur l'hôte pour le déploiement Compose, et
l'injection évite ~1,2 Go de téléchargement.

Les manifestes portent tous `imagePullPolicy: IfNotPresent`. Avec `Always`, le
kubelet tenterait un pull vers Docker Hub, échouerait en `ErrImagePull` et
**ignorerait l'image pourtant présente localement**.

### 4. Les secrets

Aucun secret n'est committé, ici pas plus qu'ailleurs dans le projet. Voir
l'en-tête de `create-secrets.sh` : un objet `kind: Secret` est du base64, pas du
chiffrement — le committer reviendrait à committer les mots de passe en clair,
avec en prime l'illusion du contraire.

Sur une machine où le déploiement Ansible a déjà tourné, le fichier `.env` rendu
depuis Vault par le rôle `app-secrets` fournit exactement les mêmes noms de
variables :

```bash
set -a && . /opt/travel-plan/.env && set +a
export VAULT_DEV_ROOT_TOKEN_ID=...   # absent du .env, propre au Vault dev mode
./k8s/create-secrets.sh
```

Le `.env` fournit neuf des dix variables. La dixième,
`VAULT_DEV_ROOT_TOKEN_ID`, n'y figure pas : elle est propre au Vault en dev mode
et vit dans l'environnement du conteneur Vault déjà provisionné par Ansible. Sur
une machine où Compose tourne, la récupérer sans jamais l'afficher :

```bash
export VAULT_DEV_ROOT_TOKEN_ID="$(docker inspect travel-plan-vault \
  --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | sed -n 's/^VAULT_DEV_ROOT_TOKEN_ID=//p')"
[ -n "$VAULT_DEV_ROOT_TOKEN_ID" ] || echo 'introuvable : Vault non provisionne'
```

Le Vault déployé dans le cluster est une instance dev mode **distincte** de
celle de Compose ; réutiliser le même root token n'est pas une nécessité, c'est
un choix de parité entre les deux déploiements. Ne jamais substituer une valeur
inventée : le script échouerait moins vite, mais Vault démarrerait avec un token
qui ne correspond à rien.

Sinon, exporter les dix variables à la main. Le script échoue immédiatement
(`${VAR:?...}`) si l'une manque : jamais de valeur par défaut, qui masquerait un
secret non provisionné jusqu'au runtime. Les dix noms attendus :

```
POSTGRES_SUPERUSER_PASSWORD  IDENTITY_DB_PASSWORD  PAYMENT_DB_PASSWORD
NEO4J_PASSWORD                JWT_SIGNING_KEY        STRIPE_API_KEY
STRIPE_SECRET_KEY             PAYPAL_CLIENT_ID       PAYPAL_CLIENT_SECRET
VAULT_DEV_ROOT_TOKEN_ID
```

## Déploiement

```bash
./k8s/create-secrets.sh        # AVANT l'apply : les Pods referencent ce Secret
kubectl apply -f k8s/

kubectl -n travel-plan rollout status statefulset/postgres --timeout=300s
kubectl -n travel-plan rollout status statefulset/neo4j    --timeout=420s
for d in identity-service payment-service travel-service; do
  kubectl -n travel-plan rollout status "deployment/$d" --timeout=420s
done
```

Neo4j est le plus lent à démarrer (20-40 s à froid, davantage sur un poste
chargé) : sa `startupProbe` lui laisse jusqu'à 5 minutes.

## Vérification

```bash
kubectl -n travel-plan get pods,deploy,statefulset,pvc,ingress

# Routage par host a travers l'Ingress (port 8081 = extraPortMapping ci-dessus)
for h in identity payment travel; do
  curl -s -o /dev/null -w "$h.localhost -> %{http_code}\n" \
    -H "Host: $h.localhost" http://localhost:8081/actuator/health
done

# Les 2 replicas sont-ils bien tous deux dans les backends du Service ?
kubectl -n travel-plan get endpointslice
```

L'en-tête `Host:` explicite rend le test indépendant de toute résolution DNS
(`.localhost` résout vers 127.0.0.1 sur la plupart des résolveurs, RFC 6761,
mais on ne s'en remet pas à ce détail).

Pour observer le load-balancing effectif, envoyer quelques requêtes puis lire
l'`upstream_addr` dans les logs du contrôleur :

```bash
for i in $(seq 1 10); do
  curl -s -o /dev/null -H 'Host: identity.localhost' http://localhost:8081/actuator/health
done
kubectl -n ingress-nginx logs deploy/ingress-nginx-controller --tail=10
```

## Ce qui a réellement été testé

Test mené sur le cluster **kind v1.31.0** décrit ci-dessus, nœud unique,
ingress-nginx installé, images chargées à la main. État atteint et constaté :

- `postgres-0`, `neo4j-0`, `vault-0` — Running, Ready, PVC `Bound` (2 Gi chacun
  pour postgres et neo4j ; Vault n'en a pas, par choix documenté).
- `identity-service`, `payment-service`, `travel-service` — **2/2 replicas
  Ready chacun**, soit les 6 Pods applicatifs déclarés.
- Les trois hosts de l'Ingress répondent **HTTP 200** sur `/actuator/health`.
- `/actuator/health` d'identity-service remonte `db: UP` (PostgreSQL) et celui
  de travel-service `neo4j: UP` : le script d'init des bases, les comptes
  applicatifs à droits limités et le mot de passe Neo4j partagé fonctionnent
  réellement, ils ne sont pas seulement déclarés.
- `/actuator/health/liveness` répond 200 : le groupe de santé Kubernetes est
  bien exposé automatiquement par Spring Boot, sans configuration applicative
  ajoutée — la `livenessProbe` des manifestes vise donc un endpoint existant.
- Load-balancing vérifié : sur 11 requêtes via l'Ingress vers
  `identity.localhost`, l'`upstream_addr` se répartit **6 / 5** entre les deux
  IP de Pod (`10.244.0.7` et `10.244.0.6`), qui sont exactement les deux
  entrées de l'EndpointSlice du Service.

Deux bugs ont été trouvés **par le test** et corrigés, tous deux invisibles à
la simple relecture :

1. **Vault** sortait immédiatement sur
   `unable to set CAP_SETFCAP effective capability`. L'entrypoint de l'image
   tente un `setcap` incompatible avec le `drop: ALL` du securityContext →
   `SKIP_SETCAP=true` + démarrage direct sous l'uid `vault` (cf. commentaires
   de `22-vault.yaml`).
2. **Neo4j** partait en `CrashLoopBackOff` sur
   `Failed to read config: Unrecognized setting. No declared setting with name: PASSWORD`.
   L'entrypoint traduit toute variable préfixée `NEO4J_` en réglage serveur : la
   variable intermédiaire `NEO4J_PASSWORD` était donc lue comme un paramètre de
   configuration inexistant. Renommée en `GRAPH_DB_PASSWORD` (cf. commentaires
   de `21-neo4j.yaml`). Piège **propre à Kubernetes** : côté Compose,
   `${NEO4J_PASSWORD}` est interpolé par Compose lui-même depuis l'environnement
   de l'hôte et n'entre jamais dans le conteneur, alors que l'expansion `$(VAR)`
   de Kubernetes exige que la variable soit réellement présente dans
   l'environnement du conteneur.

### Seconde exécution — 2026-09-17, sur poste chargé

Rejoué intégralement sur un cluster kind du même type, **pendant que la stack
Compose complète tournait** (3 services × 2 replicas, Jenkins, SonarQube,
Traefik, observabilité) : ~750 Mio de RAM disponible sur l'hôte au moment du
test, le nœud kind consommant à lui seul ~2,8 Gio. La procédure passe dans ces
conditions, sans ajustement de `resources` ni réduction de replicas.

État atteint, reproduit à l'identique : **9/9 Pods Running et Ready** —
`postgres-0`, `neo4j-0`, `vault-0`, plus 2 replicas de chacun des trois
services. PVC `Bound`, les trois hosts de l'Ingress en HTTP 200 sur
`/actuator/health`, `db: UP` côté identity et `neo4j: UP` côté travel.
Load-balancing re-vérifié : 11 requêtes réparties **6 / 5** sur les deux IP de
Pod, identiques aux deux entrées de l'EndpointSlice.

Un troisième comportement, non vu lors du premier test, est apparu :

3. **`travel-service` : un replica sur deux échoue au tout premier démarrage**,
   sort en code 1, et réussit au redémarrage automatique. Cause réelle, lue dans
   `kubectl logs --previous` :

   ```
   Neo.TransientError.Transaction.DeadlockDetected
     at ...Neo4jSchemaInitializer.ensureDestinationIdUniqueConstraint(Neo4jSchemaInitializer.java:39)
   ```

   Les 2 replicas démarrent simultanément et exécutent *en même temps* la
   création de la contrainte d'unicité sur une base Neo4j vierge ; Forseti
   détecte l'interblocage sur `LABEL(0)` et sacrifie une des deux transactions.
   Ce n'est **pas** un défaut des manifestes : c'est une course applicative dans
   l'initialisation de schéma, qui ne se manifeste que sur une base neuve et que
   seul le parallélisme rend visible. Kubernetes la rattrape de lui-même
   (`restartPolicy: Always`, Pod Ready ~20 s plus tard, `RESTARTS 1`) — mais
   compter sur le redémarrage n'est pas une correction. La correction propre est
   côté code du service : rendre `Neo4jSchemaInitializer` tolérant au
   `TransientError` (retry), ce qui relève du périmètre applicatif et non de ces
   manifestes. Noté ici pour que le `RESTARTS 1` observable après un premier
   déploiement ne soit pas pris pour un incident d'infrastructure.

Contrairement au premier test, **ce cluster n'a pas été détruit** : le
déploiement est en place et se réinspecte avec

```bash
kubectl -n travel-plan get pods
```

Les manifestes restent néanmoins rejouables de zéro par la procédure ci-dessus ;
ils ne supposent aucun état préexistant.

## Différences conceptuelles avec le déploiement Compose

| Sujet | Docker Compose (déploiement du projet) | Kubernetes (ici) |
|---|---|---|
| Ordre de démarrage | `depends_on: condition: service_healthy` | **Aucun équivalent** : idiome de remplacement = initContainer bloquant (`wait-for-postgres` / `wait-for-neo4j`) |
| Bord réseau | Traefik, seul conteneur publiant un port, routage par label | Ingress + contrôleur, seul composant recevant du trafic externe, routage par host |
| Réplicas | `deploy.replicas`, DNS Docker en round-robin | `spec.replicas`, Service ClusterIP + kube-proxy |
| Persistance | volumes Docker nommés | PVC via `volumeClaimTemplates` du StatefulSet |
| Provisionnement des bases | modules `community.postgresql` du rôle Ansible, idempotents | hook `/docker-entrypoint-initdb.d` de l'image, joué **une seule fois** |
| Secrets | `.env` rendu depuis Vault par le rôle `app-secrets` | Secret créé hors dépôt par `create-secrets.sh` |

## Limites assumées

Elles sont listées parce qu'elles sont des **choix de périmètre**, pas des oublis.

- **Pas de provisionnement automatique Vault → Secret Kubernetes.** Vault est
  déployé (en dev mode, comme le rôle Ansible), mais le chaînon qui lirait les
  secrets depuis Vault pour alimenter le Secret Kubernetes n'existe pas : ce
  serait Vault Agent Injector ou l'External Secrets Operator, donc un composant
  supplémentaire à déployer et versionner. `create-secrets.sh` fait ce pont à la
  main, et le dit. Corollaire : les trois services lisent leurs secrets depuis le
  Secret Kubernetes, **pas** depuis Vault — Vault est ici présent pour la
  complétude de la démonstration, pas comme dépendance de démarrage.
- **Pas de CI/CD Kubernetes.** Les Jenkinsfiles du projet construisent, testent
  et déploient via Compose. Aucun `kubectl apply` n'est branché sur un pipeline :
  cela supposerait un registry accessible depuis le cluster et un kubeconfig de
  service en credential Jenkins.
- **Pas de registry**, donc chargement manuel des images (§3 des prérequis).
  C'est la limite qui empêche le plus directement ces manifestes de servir
  ailleurs que sur un cluster local.
- **Pas de TLS sur l'Ingress**, contrairement à Traefik côté Compose qui termine
  le TLS en 443. La marche à suivre pour l'ajouter est en commentaire à la fin de
  `40-ingress.yaml` ; elle exige soit cert-manager, soit une génération manuelle
  de certificat — une clé privée ne se committe pas.
- **Bases de données non répliquées** (`replicas: 1`), délibérément. Répliquer
  PostgreSQL demande de la réplication streaming plus une bascule (Patroni,
  CloudNativePG) ; Neo4j Community *ne sait pas* faire de cluster, c'est une
  fonction Enterprise. Mettre `replicas: 2` sur ces StatefulSets ne produirait
  pas de la haute disponibilité mais deux bases indépendantes. Le
  load-balancing demandé par le sujet porte sur les microservices, qui sont
  sans état et bien à 2 replicas.
- **Le script d'init PostgreSQL n'est pas idempotent** au sens Ansible : il est
  simplement ignoré si le volume est déjà initialisé. Modifier le ConfigMap
  `postgres-initdb` n'a aucun effet sur une base existante ; il faut supprimer le
  PVC pour le rejouer.
- **Pas de Helm, pas d'opérateur, pas de HPA, pas de NetworkPolicy.** Hors
  périmètre d'une démonstration de bonus. Le HPA, en particulier, exige
  metrics-server et une politique d'autoscaling qui n'aurait de sens qu'avec une
  charge réelle à mesurer.
- **Pas de `podAntiAffinity`/`topologySpreadConstraints` sur les 3 Deployments
  applicatifs.** Sur le cluster kind mono-nœud testé ici, ça n'a aucun effet
  observable. Sur un cluster multi-nœuds réel, les 2 replicas d'un même service
  pourraient atterrir sur le même nœud, ce qui annulerait une partie du
  bénéfice « failover » revendiqué par les commentaires en tête de
  `30-identity-service.yaml` et équivalents — une panne de nœud tuerait alors
  les 2 replicas à la fois. À ajouter avant tout déploiement multi-nœuds.
- **Testé sur un nœud unique.** Les `resources.requests` sont calibrées pour un
  poste de développement (cf. le garde-fou 8 Go de
  `docs/architecture-decisions.md` §4), pas pour un cluster de production. Sur
  une machine chargée, descendre `travel-service` à 1 replica est le premier
  ajustement à faire : chaque replica ouvre son propre pool Bolt vers l'unique
  instance Neo4j, déjà le poste le plus lourd de la stack.

## Nettoyage

```bash
kubectl delete -f k8s/
```

**Cette commande ne supprime PAS les PVC** (`data-postgres-0`, `data-neo4j-0`) :
Kubernetes protège volontairement les données d'une suppression en cascade. Pour
tout effacer, y compris les volumes et le Secret :

```bash
kubectl delete namespace travel-plan
```

Ou, si le cluster a été créé uniquement pour cette démonstration :

```bash
kind delete cluster --name travel-plan
```
