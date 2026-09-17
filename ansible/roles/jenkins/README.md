# role: jenkins

Le **controleur CI** de Travel-Plan, profil Compose **`ci`** (a la demande, cf.
`docs/architecture-decisions.md` §8). Ansible **provisionne et rend des fichiers** ;
docker-compose **orchestre**. Meme modele operatoire que `postgres` / `vault` /
`neo4j` / `traefik` : aucun `docker_container`, aucun `docker compose up` depuis
Ansible.

**Increment CI/CD n°2 — generalisation multi-jobs.** Un controleur, **trois** jobs
Pipeline declenches **a la main par defaut** (un par service : `identity-service`,
`payment-service`, `travel-service`), chacun jouant `./mvnw test` sur son module.
Un **poll SCM opt-in** (`jenkins_scm_poll_enabled`) est le seul declenchement
automatique disponible : pas de webhook (Jenkins n'a pas d'URL publique
joignable depuis GitHub.com), pas de Sonar, pas d'image construite.

## Ce que le role fait
- Rend `compose.jenkins.yml` : image **epinglee** `jenkins/jenkins:2.568.3-lts-jdk21`,
  `JENKINS_HOME` sur **volume nomme** `travel-plan-jenkins-home`, reseau
  `backend-net`, **aucun port publie**, `mem_limit: 1600m`, heap controleur
  `-Xmx512m`, healthcheck HTTP `/login`, **route Traefik par labels**.
- Resout le **GID du groupe `docker` de l'hote** (`getent group docker`) et
  l'injecte en `group_add` — sans quoi le process jenkins (uid 1000) ne peut pas
  ecrire sur le socket et Testcontainers echoue en `EACCES`.
- Rend **un `job-<nom>-config.xml` par entree de `jenkins_jobs`** : la definition
  du job Pipeline, **prete a poster**, avec `<triggers/>` **vide** par defaut
  (ou un `SCMTrigger` si `jenkins_scm_poll_enabled=true`). Aujourd'hui :
  `job-identity-service-test-config.xml`, `job-payment-service-test-config.xml`,
  `job-travel-service-test-config.xml`.

## Modele multi-jobs (increment n°2)

Les jobs ne sont plus cables en dur : ils sont decrits par la liste
`jenkins_jobs` (`defaults/main.yml`), parcourue par **un** `loop` sur **un**
template generique `templates/job-pipeline.xml.j2`.

| cle | role |
|---|---|
| `name` | nom de l'item Jenkins **et** suffixe du fichier rendu (`job-<name>-config.xml`), reutilise tel quel dans l'URL REST |
| `description` | texte affiche sur la page du job |
| `script_path` | chemin du `Jenkinsfile`, **relatif a la racine du depot** (le checkout ramene le monorepo entier) |

**Pourquoi une liste + un template, plutot que trois templates.** Les 3 jobs sont
structurellement identiques : meme SCM `file://`, meme branche, meme retention,
meme politique de declenchement. Il n'existe donc **qu'un seul endroit** ou un
trigger ou un credential pourrait se glisser — un seul endroit a auditer, et aucun job ne
peut deriver en silence. Trois copies auraient permis exactement l'inverse.

**Ajouter un service au perimetre CI** = ajouter une entree a `jenkins_jobs` +
commiter son `Jenkinsfile`. Ni `tasks/main.yml` ni le template ne bougent. Le
role **asserte** au provisioning que chaque entree porte les 3 cles et que les
noms sont uniques : une entree incomplete produirait un XML valide mais
silencieusement casse (job sans `scriptPath`), qui n'echouerait qu'au build.

**Trois jobs INDEPENDANTS, pas un pipeline chapeau.** Aucun job n'en declenche un
autre : les services ne partagent que le monorepo, aucun ordre de dependance ne
justifie un enchainement. Et `disableConcurrentBuilds()` ne protege un job que
**contre lui-meme** : lancer les 3 en meme temps ferait cohabiter 3 JVM Maven +
3 JVM Surefire dans la meme cgroup de 1600m => OOM. **C'est a l'operateur de les
lancer un a la fois** (contrainte assumee de l'increment « pas d'agent dedie »).

## Ce que le role NE fait PAS (assume, pas un oubli)
- **Pas de SonarQube** — increment separe.
- **Pas de webhook, pas de multibranch scan, pas de mecanisme de Pull Request.**
  Un webhook supposerait que GitHub.com puisse **joindre** ce Jenkins : il n'a
  aucune URL publique (poste local, Traefik, cert auto-signe). Detail et
  procedure « pour plus tard » dans « Declenchement » ci-dessous.
- **Declenchement manuel par defaut**, sur **les trois** jobs, et **y compris
  quand le SCM est le vrai remote GitHub** (mode B : basculer la source ne change
  rien au declenchement). Le `<triggers/>` vide de chaque config.xml en est la
  preuve structurelle (asserte par Molecule, job par job, **dans les deux
  modes**). **Seule derogation, opt-in et explicite : le poll SCM**
  (`jenkins_scm_poll_enabled=true`), lui aussi asserte par Molecule sur les 3 jobs.
- **Il ne cree, ne stocke et ne rend AUCUNE cle ni token.** En mode B il ne rend
  que **l'identifiant** du credential Jenkins ; la cle de deploiement est
  generee et deposee **a la main** par l'operateur (procedure en fin de fichier).
- **Pas de build ni de push d'image Docker** depuis Jenkins.
- **Pas d'agent Jenkins distinct** : les builds tournent sur le controleur.
- **Il ne CREE pas les jobs dans Jenkins, et n'installe pas les plugins.** Les deux
  exigent de s'authentifier aupres de Jenkins, donc un **secret cote Ansible**,
  avant que Vault n'y soit cable. Contrat du projet : on **s'arrete et on le
  signale** plutot que d'inliner un credential ou de poser un placeholder. Ce
  sont deux commandes documentees plus bas, jouees par l'operateur avec le mot
  de passe **genere par Jenkins a l'execution** (jamais commite).

## Decisions tranchees (et pourquoi)

### Image : `jenkins/jenkins:2.568.3-lts-jdk21`
LTS epinglee, **JDK 21** pour etre aligne sur les **trois** services (identity,
payment et travel utilisent tous `eclipse-temurin:21.0.7_6-jdk-noble` dans leur
Dockerfile) : le `./mvnw test` de chaque job tourne sur la meme majeure Java que
le build applicatif correspondant, avec **une seule** image de controleur. Verifie empiriquement dans l'image :
`JENKINS_VERSION=2.568.3`, Temurin 21, et **`git` / `curl` / `unzip` presents**.
`unzip` est une dependance **dure** : le `mvnw` de ce depot est un wrapper shell
artisanal qui telecharge la distribution Maven en `.zip` et l'extrait avec `unzip`.

### Source du code : deux modes, un seul commutateur (`jenkins_scm_url`)

| | **Mode A — miroir local** (defaut) | **Mode B — vrai remote GitHub** (opt-in) |
|---|---|---|
| `jenkins_scm_url` | `file:///srv/travel-plan-repo` | `git@github.com:FredericTischler/travel-plan.git` |
| `jenkins_scm_credential_id` | *(vide)* | `github-deploy-key` |
| `jenkins_scm_repo_host_path` | **obligatoire** (bind-mount `:ro`) | inutile, non monte |
| Credential Jenkins | **aucun** | cle de deploiement **lecture seule**, deposee **a la main** |
| Ce que le job teste | ce qui est **commite en local** | ce qui est **pousse sur GitHub** |
| Declenchement | inchange par le mode (commutateur **separe** : `jenkins_scm_poll_enabled`) | idem — basculer la source ne declenche rien de plus |

Le mode est **derive de la forme de l'URL** (`jenkins_scm_local_mirror`), pas
saisi separement : basculer ne demande pas de penser a retourner trois booleens —
et a en oublier un.

**Le defaut reste le mode A**, comme tous les flags de ce projet (`false`/vide par
defaut, on n'active rien implicitement) ; surtout, le mode B est **inutilisable
tant que l'operateur n'a pas depose lui-meme le credential** dans Jenkins.

- **Tradeoff du mode A** : le job teste ce qui est **commite dans le checkout
  local**, pas ce qui est pousse. Zero credential, zero surface d'authentification.
- **Tradeoff du mode B** : le job teste la **source de verite**, au prix d'un
  secret a gerer dans Jenkins (que ce depot ne voit jamais : seul **l'identifiant**
  du credential est rendu, ce qui n'est pas un secret).
- **Ce que le mode B n'apporte PAS, volontairement** : ni webhook, ni scan
  multibranch, ni mecanisme de **Pull Request**, ni declenchement automatique.
  Seule la **source** du checkout change ; le `<triggers/>` reste vide dans les
  deux modes tant que `jenkins_scm_poll_enabled` vaut `false` — **asserte par
  Molecule sur les deux**. Activer le poll est une decision **separee**
  (section « Declenchement » ci-dessous), meme si c'est avec le mode B qu'elle a
  le plus de sens.

**Corollaire non evident du mode A, trouve en le cassant** : le git-plugin
**refuse** par defaut un remote pointant sur un repertoire local
(`... references a local directory, which may be insecure`). Il faut lever le
garde-fou : `-Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true`
(`jenkins_allow_local_checkout`, injecte dans `JAVA_OPTS`). Ce garde-fou protege
un Jenkins **multi-utilisateur** (un utilisateur pouvant configurer un job lirait
n'importe quel repertoire local). **Accepte en mode A** : Jenkins solo, un seul
compte, et le seul repertoire local atteignable est un bind-mount **`:ro`**.

> **« Reste-t-il necessaire en mode B ? » Non — et c'est desormais automatique.**
> `jenkins_allow_local_checkout` est **derive** de `jenkins_scm_url` : sur un
> remote `git@github.com:...` la propriete n'est **plus injectee du tout**, donc
> le garde-fou du git-plugin **se retablit sans intervention**. Le bind-mount du
> checkout hote disparait du fragment pour la meme raison (plus rien a monter, et
> pas d'elargissement gratuit de la surface du conteneur). Les deux points sont
> assertes par Molecule en mode B.

### Declenchement : manuel par defaut, **poll SCM** en opt-in (pas de webhook)

Le sujet demande que les tests tournent **a chaque changement**. Deux facons
d'obtenir ca — **une seule est realisable ici**.

| | **Webhook GitHub -> Jenkins** | **Poll SCM** (implemente, opt-in) |
|---|---|---|
| Sens du flux | GitHub **appelle** Jenkins (entrant) | Jenkins **interroge** GitHub (sortant) |
| Prerequis reseau | URL **publique** + DNS + certificat valide | aucun |
| Prerequis GitHub | creer un webhook dans les *settings* du depot | **aucune action** |
| Latence apres un push | ~1 s | jusqu'a 5 min (periode de poll) |
| Faisable par ce role | **non** | **oui, de bout en bout** |

**Pourquoi pas le webhook, concretement.** Ce Jenkins tourne sur un poste de dev :
aucun port publie, UI exposee en `https://jenkins.localhost` derriere Traefik avec
un certificat **auto-signe**. `jenkins.localhost` resout en `127.0.0.1` — depuis
GitHub.com, il n'existe ni adresse joignable, ni nom DNS, ni certificat
acceptable. Un webhook ne serait pas « pas encore configure » : il serait
**structurellement injoignable**. Il faudrait d'abord un tunnel (ngrok /
Cloudflare Tunnel) *et* une action manuelle dans les settings du depot GitHub —
deux choses qui n'appartiennent pas a ce role (cf. « Si un jour un webhook » plus
bas).

**Ce que fait le poll.** `jenkins_scm_poll_enabled=true` rend, **pour les trois
jobs a la fois** (un seul template), un trigger `hudson.triggers.SCMTrigger` de
spec `jenkins_scm_poll_cron` (defaut `H/5 * * * *`). Jenkins fait un
`git ls-remote` sur la branche suivie et ne declenche un build **que si le SHA a
change** : un poll sans commit ne cree **aucun** build (verifie : le journal de
poll affiche `already built by #12` / `No changes`).

```bash
ansible-playbook ansible/roles/jenkins/test-local.yml -K \
  -e jenkins_scm_url=git@github.com:FredericTischler/travel-plan.git \
  -e jenkins_scm_credential_id=github-deploy-key \
  -e jenkins_scm_poll_enabled=true
# puis re-POSTer les 3 config.xml (Ansible ne met PAS a jour les jobs : cf. §4
# de la sequence rejouable, variante /job/<nom>/config.xml)
```

**Defaut `false`**, meme discipline que tous les flags du role : rien ne
s'active implicitement, et le declenchement manuel strict reste ce qui est livre
par defaut (asserte par Molecule). Le flag est **orthogonal** au mode A/B : c'est
un commutateur separe de `jenkins_scm_url`, parce que « quelle source » et « quand
declencher » sont deux decisions independantes.

**Tradeoffs assumes.**
- **Latence** : jusqu'a 5 min contre ~1 s pour un webhook. Sur un projet mono-dev,
  c'est sans consequence pratique.
- **`H/5` plutot que `H/2` ou `H/1`** : chaque poll est un `ls-remote` SSH ; 5 min
  reste bien plus court que la duree d'un build (~2 min) et evite d'empiler des
  polls. `H` (hash du nom du job) **decale les 3 jobs** dans la fenetre au lieu de
  les faire poller a la meme seconde — utile ici, ou 3 builds simultanes dans une
  cgroup de 1600m signifient un OOM.
- **Couple utile = poll + mode B.** En mode A (miroir `file://`), le poll ne voit
  que les commits du **checkout local**, pas ce qui est pousse. Activer le poll a
  du sens surtout sur le remote reel.
- **Une seule branche** (`jenkins_scm_branch`, `main`). Le poll **ne couvre pas
  les Pull Requests** : cela demanderait un job *multibranch* + le plugin
  `github-branch-source`, hors perimetre — et le scan de PR sur un fork public
  ferait tourner du code tiers sur un Jenkins qui a le socket Docker.

**Si un jour un webhook devient possible** (Jenkins expose via un tunnel HTTPS) :
installer le plugin `github`, cocher *GitHub hook trigger for GITScm polling* sur
chaque job (`<com.cloudbees.jenkins.GitHubPushTrigger>` dans le `config.xml`),
puis creer le webhook **cote GitHub** (*Settings → Webhooks → Add webhook*, URL
`https://<tunnel>/github-webhook/`, content type `application/json`, evenement
`push`). Cette derniere etape est une modification des **parametres du depot
GitHub** : elle appartient a l'operateur, exactement comme le depot de la deploy
key — ce role ne touche jamais a GitHub. Le poll peut alors etre desactive
(`jenkins_scm_poll_enabled=false`) ou conserve comme **filet** en cas de webhook
perdu (c'est meme le reglage recommande : un webhook rate est silencieux).

### UI : route Traefik, PAS de port publie
`https://jenkins.localhost/` via les labels Docker, **meme idiome que
identity / payment / travel**. Justification : la convention du projet est que
**seul Traefik publie un port** (docs §4) ; router Jenkins coute 6 labels et rien
d'autre (le provider Docker de Traefik est deja actif), alors qu'un
`docker compose exec` / port-forward serait un contournement de cette convention
pour un service dont l'UI est justement faite pour etre ouverte dans un
navigateur. L'alternative n'aurait economise aucune complexite reelle.

### Socket Docker en **lecture-ECRITURE** (ecart assume vs `traefik`)
Le role `traefik` monte le socket en `:ro` — minimum vital pour la decouverte par
labels. **Ici c'est impossible** : les tests des trois services utilisent
**Testcontainers** (`PostgreSQLContainer` pour identity et payment,
`Neo4jContainer` pour travel), qui doit **creer, demarrer et supprimer** des
conteneurs freres. `:ro` rendrait le job structurellement
impossible. Risque quasi-root **accepte**, borne par : profil `ci` a la demande,
machine de dev, UI derriere Traefik. **A reevaluer** si ce projet visait une vraie
prod (agent Jenkins dedie, `docker-socket-proxy`, ou rootless).

### Testcontainers depuis un conteneur (le piege reseau)
Le job tourne **dans** le conteneur Jenkins mais cree ses conteneurs de test sur
le demon de l'**hote**. Par defaut `getHost()` renvoie `localhost` — qui designe
le conteneur Jenkins lui-meme, jamais l'hote ou le port du postgres de test est
publie : connexion refusee. D'ou le couple
`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` +
`extra_hosts: host.docker.internal:host-gateway`.

Ce cablage vit dans le **fragment Compose du controleur**, donc il est **commun
aux trois jobs** : rien a dupliquer par service. Il vaut aussi bien pour le
`PostgreSQLContainer` d'identity/payment que pour le `Neo4jContainer` de travel
(les deux passent par `getHost()` / `getMappedPort()`).

### RAM : `mem_limit: 1600m` (ecart **assume** vs les ~700 Mo de §8)
§8 chiffre « Jenkins ~700 Mo » = le controleur **au repos**. Ici les builds
tournent **sur** le controleur (pas d'agent separe) : la meme cgroup doit loger
le controleur (~700 Mo avec `-Xmx512m`) + la JVM Maven (~300 Mo) + la JVM forkee
par Surefire (~400 Mo). 700m OOM-killerait le premier `mvn test`. L'ecart tient
parce que le profil `ci` est explicitement « a la demande, idealement stack
applicative down » (§8). Mesure au repos apres le build de preuve : **445 MiB**.
Les conteneurs Testcontainers sont des **freres** sur le demon hote : ils ne
comptent pas dans cette cgroup (~250 Mo pour `postgres:17.5-bookworm`, ~600 Mo
pour `neo4j:5.26.6-community` cote travel-service, a cote).

Cette limite est dimensionnee pour **un build a la fois**. Les 3 jobs existent
en parallele dans Jenkins, mais ne doivent pas **tourner** en parallele.

### Reseau : `backend-net` seul
Necessaire et suffisant pour que Traefik decouvre et route l'UI. Jenkins ne parle
a aucune base du projet (pas de `data-net`) et ne publie rien (pas de `edge-net`).
Il n'a pas non plus besoin de joindre le conteneur `identity-service` : le job ne
teste pas le service **deploye**, il compile et teste les **sources**.

## Secrets
**Aucun dans le depot, aucun rendu par Ansible.** Le fragment n'expose que 3
variables d'environnement non sensibles (`JAVA_OPTS`, `TESTCONTAINERS_HOST_OVERRIDE`,
`DOCKER_HOST`) — asserte par Molecule. Le mot de passe admin est **genere par
Jenkins** dans `JENKINS_HOME/secrets/initialAdminPassword` (volume nomme) et lu
**a l'execution**. En mode A, le config.xml du job ne contient **aucun**
identifiant : le transport `file://` n'authentifie rien.

En mode B, le seul ajout est `<credentialsId>github-deploy-key</credentialsId>` :
**un nom d'entree du credential store, pas un secret**. La cle privee n'existe
que dans `JENKINS_HOME`, deposee **a la main** par l'operateur (procedure ci-
dessous) ; le role ne la genere pas, ne la lit pas, ne la rend pas. Molecule
asserte qu'aucun rendu ne contient de `PRIVATE KEY`.

## Test

**Molecule : OUI**, mais sur un perimetre explicite. Le role ne fait que *rendre
des fichiers* : le scenario rend dans `/tmp` d'une plateforme `debian:bookworm-slim`
puis asserte la **structure** du fragment (`from_yaml`, pas du grep) et du
config.xml. Il **ne demarre pas** de vrai Jenkins : expansion du war (~90 s),
wizard, installation des plugins puis un `mvn test` Testcontainers (Maven Central
+ pull `postgres:17.5`) ne tiennent pas dans une boucle `molecule test`
raisonnable et exigeraient le reseau a chaque run. Le **vrai** demarrage et
l'execution du job sont prouves **hors Molecule**, par la sequence rejouable
ci-dessous.

`verify` asserte notamment : image epinglee (jamais `latest`), `mem_limit` +
`-Xmx`, `JENKINS_HOME` sur volume nomme, `backend-net` **seul**, **aucun**
`ports:`, route Traefik complete, socket **sans `:ro`**, `group_add`, cablage
Testcontainers, miroir SCM en `:ro`, healthcheck, **exactement 3** variables
d'environnement (zero secret).

Cote jobs, depuis l'increment n°2, les assertions bouclent sur **les trois**
config.xml : chacun doit pointer sur **son** `Jenkinsfile` (et sur lui seul :
un copier-coller rate se verrait), porter un `<triggers/>` **vide** (defaut), et ne
contenir **ni credential, ni Sonar, ni Docker**. Une derniere assertion verifie
que le repertoire de rendu contient **exactement** le fragment + 3 `config.xml`
et rien d'autre — un fichier orphelin trahirait un job retire de `jenkins_jobs`
mais laisse sur le disque (le role ne nettoie pas ce qu'il ne gere plus : c'est
l'operateur qui supprime le fichier **et** l'item Jenkins).

Le scenario converge **trois fois**, dans trois repertoires distincts : le mode A
(reference), le **mode B** avec `jenkins_scm_url=git@github.com:...` et
`jenkins_scm_credential_id=github-deploy-key`, puis le **poll SCM** (mode B +
`jenkins_scm_poll_enabled=true`). Sans ces passages, les branches opt-in du
template ne seraient jamais exercees. Le mode B asserte : remote reel +
`<credentialsId>` **reference seule** (aucune `PRIVATE KEY`, aucun `file://`),
`<triggers/>` **toujours vide** (le point dur : passer au remote ne doit rien
declencher automatiquement), **plus de bind-mount** du depot et
**`ALLOW_LOCAL_CHECKOUT` absent** de `JAVA_OPTS`. Le mode B n'est jamais convergé
avec `jenkins_scm_repo_host_path` : prouver qu'il **n'est plus requis** fait
partie du contrat.

Le 3e passage asserte que le `SCMTrigger` de spec `H/5 * * * *` est rendu sur
**les trois** jobs (pas seulement le premier), avec une spec **non vide** — une
spec vide donne un XML valide qui ne polle **jamais**, la panne silencieuse type —
et qu'activer le poll n'a fait apparaitre **ni webhook ni multibranch** par effet
de bord. Cote role, une garde `assert` echoue **fail-fast** si le poll est active
avec un cron vide.

```bash
export PATH="$PWD/.venv-ansible/bin:$PATH"
cd ansible/roles/jenkins && molecule test   # converge + idempotence + verify
```

Un 2e `ansible-playbook` rapporte **`changed=0`** (aucun `command:`/`shell:` dans
le role : uniquement `assert` / `getent` / `set_fact` / `file` / `template`).

---

# Sequence REJOUABLE : de zero a un build vert

> Prerequis : les roles `traefik` et `compose-assembly` ont deja ete appliques,
> les reseaux Docker existent, et `VAULT_DEV_ROOT_TOKEN_ID` / `NEO4J_PASSWORD`
> sont exportes dans le shell (l'interpolation Compose est **globale**, elle ne
> se limite pas au profil demande).

## 0. Les Jenkinsfile doivent etre COMMITES sur la branche visee

Chaque job fait un vrai `checkout scm` : il ne voit que ce qui est **commite**.
Les trois fichiers suivants doivent donc etre commites sur `main` (ou surcharger
`jenkins_scm_branch`) :

```
services/identity-service/Jenkinsfile
services/payment-service/Jenkinsfile
services/travel-service/Jenkinsfile
```

Symptome typique si on oublie : le build demarre, le checkout reussit, puis
echoue immediatement sur `...Jenkinsfile not found`.

## 1. Provisionner (rend le fragment + les 3 config.xml de jobs)

```bash
cd /chemin/vers/le/depot/travel-plan
source .venv-ansible/bin/activate

ansible-playbook ansible/roles/jenkins/test-local.yml -K \
  -e jenkins_scm_repo_host_path=/chemin/vers/le/depot/travel-plan

ansible-playbook ansible/roles/compose-assembly/test-local.yml -K \
  -e assembly_jenkins_enabled=true \
  -e assembly_identity_fragment=/chemin/vers/le/depot/travel-plan/services/identity-service/docker-compose.identity.yml
```

## 2. Demarrer le profil `ci`

```bash
cd /opt/travel-plan
docker compose --profile ci up -d
# -> demarre `jenkins` + `traefik` (traefik est dans [core, full, ci] car l'UI
#    Jenkins passe par lui).

# attendre que le healthcheck passe (premier demarrage : ~90 s)
until [ "$(docker inspect -f '{{.State.Health.Status}}' travel-plan-jenkins)" = healthy ]; do sleep 5; done
```

## 3. Deverrouiller Jenkins + installer les 3 plugins necessaires

Le mot de passe admin initial est genere par Jenkins **dans le volume**. On ne le
copie nulle part : on le lit a chaque commande, **dans** le conteneur.

```bash
docker exec travel-plan-jenkins bash -c '
PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
CRUMB=$(curl -s -u "admin:$PW" -c /tmp/ck \
  "http://localhost:8080/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")

# git (SCM) + workflow-aggregator (Pipeline declaratif) + junit (rapports).
# dynamicLoad:true => pas de redemarrage de Jenkins. Les dependances
# transitives sont resolues par l update center.
curl -s -u "admin:$PW" -b /tmp/ck -H "$CRUMB" -H "Content-Type: application/json" \
  -X POST --data "{\"dynamicLoad\":true,\"plugins\":[\"git\",\"workflow-aggregator\",\"junit\"]}" \
  http://localhost:8080/pluginManager/installPlugins; echo
'
```

Attendre que les 3 plugins soient actifs (~1 a 2 min) :

```bash
docker exec travel-plan-jenkins bash -c '
PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
curl -s -u "admin:$PW" "http://localhost:8080/pluginManager/api/json?depth=1&tree=plugins\[shortName,active\]" \
  | tr "{" "\n" | grep -E "\"(git|workflow-aggregator|junit)\""
'
# attendu : "active":true pour les 3
```

Terminer le wizard et fixer l'URL publique (sinon Jenkins genere des liens
absolus faux derriere le reverse proxy, et la CLI HTTP refuse de se connecter
avec `Jenkins URL is not configured`) :

```bash
docker exec travel-plan-jenkins bash -c '
PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
CRUMB=$(curl -s -u "admin:$PW" -c /tmp/ck \
  "http://localhost:8080/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")
curl -s -o /dev/null -w "completeInstall: %{http_code}\n" -u "admin:$PW" -b /tmp/ck -H "$CRUMB" \
  -X POST http://localhost:8080/setupWizard/completeInstall
curl -s -u "admin:$PW" -b /tmp/ck -H "$CRUMB" -X POST \
  --data-urlencode "script=import jenkins.model.JenkinsLocationConfiguration; def c = JenkinsLocationConfiguration.get(); c.setUrl(\"https://jenkins.localhost/\"); c.save(); println(c.getUrl())" \
  http://localhost:8080/scriptText
'
```

> Le compte reste **`admin`** avec ce mot de passe initial (option « continuer en
> tant qu'admin »). Aucun mot de passe n'est invente ni ecrit dans le depot.

## 4. Creer les jobs Pipeline — **via l'API REST** (pas l'UI)

Les `config.xml` ont ete **rendus par Ansible** a l'etape 1. On les pousse tels
quels. La boucle ci-dessous est **rejouable** pour les trois jobs : le nom de
l'item Jenkins est exactement le suffixe du fichier rendu (`job-<nom>-config.xml`),
c'est la seule convention a retenir.

```bash
for JOB in identity-service-test payment-service-test travel-service-test; do
  docker cp "/opt/travel-plan/jenkins/job-${JOB}-config.xml" \
            "travel-plan-jenkins:/tmp/job-${JOB}.xml"

  docker exec -e JOB="$JOB" travel-plan-jenkins bash -c '
  PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
  CRUMB=$(curl -s -u "admin:$PW" -c /tmp/ck \
    "http://localhost:8080/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")
  curl -s -o /dev/null -w "createItem $JOB: %{http_code}\n" -u "admin:$PW" -b /tmp/ck -H "$CRUMB" \
    -H "Content-Type: application/xml" --data-binary @/tmp/job-$JOB.xml \
    "http://localhost:8080/createItem?name=$JOB"
  '
done
# attendu : createItem <job>: 200  (x3)
```

> Si un job **existe deja** (increment n°1 : `identity-service-test`),
> `createItem` repond **400** `A job already exists with the name ...`. Ce n'est
> pas un echec de la sequence : soit on le laisse tel quel (le config.xml rendu
> est identique a celui de l'increment n°1), soit on le **met a jour** en
> POSTant sur `/job/<nom>/config.xml` au lieu de `/createItem` :
>
> ```bash
> docker exec travel-plan-jenkins bash -c '
> PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
> CRUMB=$(curl -s -u "admin:$PW" -c /tmp/ck \
>   "http://localhost:8080/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")
> curl -s -o /dev/null -w "update: %{http_code}\n" -u "admin:$PW" -b /tmp/ck -H "$CRUMB" \
>   -H "Content-Type: application/xml" --data-binary @/tmp/job-identity-service-test.xml \
>   "http://localhost:8080/job/identity-service-test/config.xml"
> '
> ```

> Equivalent UI, si tu preferes cliquer : *New Item* → nom `<service>-service-test`
> → *Pipeline* → *Pipeline script from SCM* → SCM `Git`, URL
> `file:///srv/travel-plan-repo`, branche `*/main`, *Script Path*
> `services/<service>-service/Jenkinsfile`, et **aucun** trigger coche (ou *Poll
> SCM* `H/5 * * * *` si tu actives le poll).

### 4bis. Activer le **poll SCM** sur des jobs deja crees

C'est exactement la variante « mise a jour » ci-dessus, jouee sur les 3 jobs
apres un `ansible-playbook ... -e jenkins_scm_poll_enabled=true`. Le trigger
**remplace** `<triggers/>` ; rien d'autre ne bouge dans le `config.xml`.

```bash
for JOB in identity-service-test payment-service-test travel-service-test; do
  docker cp "/opt/travel-plan/jenkins/job-${JOB}-config.xml" \
            "travel-plan-jenkins:/tmp/job-${JOB}.xml" >/dev/null
  docker exec -e JOB="$JOB" travel-plan-jenkins bash -c '
  PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
  CRUMB=$(curl -s -u "admin:$PW" -c /tmp/ck \
    "http://localhost:8080/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")
  curl -s -o /dev/null -w "update $JOB: %{http_code}\n" -u "admin:$PW" -b /tmp/ck -H "$CRUMB" \
    -H "Content-Type: application/xml" --data-binary @/tmp/job-$JOB.xml \
    "http://localhost:8080/job/$JOB/config.xml"
  '
done
# attendu : update <job>: 200  (x3)
```

Verifier que Jenkins a bien **repris** le trigger (et pas seulement le fichier) :

```bash
for JOB in identity-service-test payment-service-test travel-service-test; do
  docker exec -e JOB="$JOB" travel-plan-jenkins bash -c '
  PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
  echo -n "$JOB: "
  curl -s -u "admin:$PW" "http://localhost:8080/job/$JOB/config.xml" | grep -c SCMTrigger'
done
# attendu : 2 occurrences (balise ouvrante + fermante) par job
```

Forcer un poll immediat et lire son journal (sans attendre 5 min) :

```bash
docker exec travel-plan-jenkins bash -c '
PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
CRUMB=$(curl -s -u "admin:$PW" -c /tmp/ck \
  "http://localhost:8080/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")
curl -s -o /dev/null -w "poll: %{http_code}\n" -u "admin:$PW" -b /tmp/ck -H "$CRUMB" \
  -X POST http://localhost:8080/job/identity-service-test/polling
sleep 15
tail -5 /var/jenkins_home/jobs/identity-service-test/scm-polling.log'
```

Sortie obtenue sur ce poste (mode B, deploy key en place) :

```
 > git ls-remote -h -- git@github.com:FredericTischler/travel-plan.git # timeout=10
[poll] Latest remote head revision on refs/heads/main is: 125a176... - already built by 12
Done. Took 13 sec
No changes
```

C'est la preuve des deux moities : le poll **s'authentifie** avec la deploy key
et lit le vrai remote, **et** il ne declenche **rien** quand le SHA n'a pas
bouge. Revenir au manuel = rejouer le playbook sans le flag et re-POSTer.

## 5. Declencher les builds **a la main**, UN A LA FOIS

```bash
# remplacer JOB par identity-service-test / payment-service-test / travel-service-test
JOB=payment-service-test
docker exec -e JOB="$JOB" travel-plan-jenkins bash -c '
PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
CRUMB=$(curl -s -u "admin:$PW" -c /tmp/ck \
  "http://localhost:8080/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")
curl -s -D - -o /dev/null -u "admin:$PW" -b /tmp/ck -H "$CRUMB" \
  -X POST "http://localhost:8080/job/$JOB/build" | grep -iE "^HTTP|^location"
'
# attendu : HTTP/1.1 201 Created
```

> **Ne pas boucler sur les 3 d'un coup.** `disableConcurrentBuilds()` protege un
> job contre lui-meme, pas contre ses voisins : 3 builds simultanes = 3 JVM Maven
> + 3 JVM Surefire dans la cgroup de 1600m, plus 2 postgres et 1 neo4j de test
> cote hote. On attend `result != null` avant de lancer le suivant.

> **Corollaire DIRECT de l'activation du poll — a ne pas sauter.** Un push sur
> `main` fait detecter le meme SHA par les **trois** jobs : trois builds sont mis
> en file. La discipline « un a la fois » ne peut alors plus reposer sur
> l'operateur, puisque plus personne ne clique. Il faut la faire tenir par le
> **nombre d'executeurs du controleur** : `numExecutors: 1` (le defaut Jenkins est
> **2**, soit 2 JVM Maven + 2 JVM Surefire dans la cgroup de 1600m => OOM
> probable). Avec 1 executeur, les 3 builds **se serialisent** (~6 min au total
> apres un push) au lieu de se marcher dessus.
>
> C'est un reglage **global de Jenkins** (`JENKINS_HOME/config.xml`), donc **hors
> du perimetre de ce role** : Ansible ne gere pas le contenu du volume nomme, et
> l'ecrire exigerait un credential Jenkins cote Ansible. A appliquer une fois, a
> la main :
>
> ```bash
> docker exec travel-plan-jenkins bash -c '
> PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
> CRUMB=$(curl -s -u "admin:$PW" -c /tmp/ck \
>   "http://localhost:8080/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")
> curl -s -u "admin:$PW" -b /tmp/ck -H "$CRUMB" -X POST --data-urlencode \
>  "script=def j=jenkins.model.Jenkins.get(); j.setNumExecutors(1); j.save(); println(j.getNumExecutors())" \
>  http://localhost:8080/scriptText'
> # attendu : 1
> ```

## 6. Suivre et confirmer

```bash
JOB=payment-service-test

# etat du dernier build
docker exec -e JOB="$JOB" travel-plan-jenkins bash -c '
PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
curl -s -u "admin:$PW" "http://localhost:8080/job/$JOB/lastBuild/api/json?tree=number,building,result"
'

# log complet
docker exec -e JOB="$JOB" travel-plan-jenkins bash -c '
PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
curl -s -u "admin:$PW" "http://localhost:8080/job/$JOB/lastBuild/consoleText"
'
```

Le premier build telecharge Maven 3.9.9 + tout le repo de dependances
(~2 min) ; les suivants reutilisent le cache `~/.m2` du volume nomme — y compris
**entre jobs**, les 3 services partageant l'essentiel de leurs dependances Spring
Boot. Attendre en revanche un premier build plus long pour `travel-service` : il
pull `neo4j:5.26.6-community` (image nettement plus grosse que `postgres:17.5`)
et son bootstrap de store est plus lent. Le `timeout(30 MINUTES)` du Jenkinsfile
couvre ce cas.

### Resultat obtenu — `identity-service-test` (build #2, machine de dev Linux)

```
+ ./mvnw test -B --no-transfer-progress
Downloading Maven from https://repo.maven.apache.org/maven2/.../apache-maven-3.9.9-bin.zip ...
...
INFO tc.postgres:17.5-bookworm -- Container postgres:17.5-bookworm started in PT1.10S
...
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  02:01 min
[Pipeline] junit
Recording test results
Finished: SUCCESS
```

## 7. UI (humain)

```bash
curl -k -I https://jenkins.localhost/login     # HTTP 200 (cert auto-signe Traefik)
# ou navigateur : https://jenkins.localhost/   (accepter le cert auto-signe)
```

`*.localhost` resout en 127.0.0.1 sur Linux ; sinon forcer l'en-tete :
`curl -k -H 'Host: jenkins.localhost' https://localhost/login`.

## 8. Arreter la CI (docs §8 : elle ne reste pas allumee)

```bash
cd /opt/travel-plan
docker compose stop jenkins        # garde Traefik et la stack applicative debout
# ou, si la stack applicative est deja down :
docker compose --profile ci down   # arrete aussi Traefik
```

Le volume `travel-plan-jenkins-home` **survit** : plugins, jobs, historique et
cache `~/.m2` sont deja la au prochain `up`. Les etapes 3 et 4 ne se rejouent pas
(l'etape 4 ne se rejoue que pour un job **nouvellement** ajoute a
`jenkins_jobs`, ou en mode mise a jour `/job/<nom>/config.xml`).

---

# Mode B : brancher la CI sur le **vrai remote GitHub** (procedure MANUELLE)

> **Optionnel.** Le mode A (miroir local) reste le defaut et reste pleinement
> supporte. Ce mode-ci fait tester a Jenkins **ce qui est pousse sur GitHub** au
> lieu de ce qui est commite dans le checkout de la machine.
>
> **Ce qu'il n'apporte PAS, volontairement** : aucun webhook, aucun scan
> multibranch, **aucun mecanisme de Pull Request**, et **aucun declenchement
> automatique** — **seule la source du checkout change**. Le declenchement reste
> manuel (bouton *Build Now* / CLI) sauf si l'on active **separement** le poll
> SCM (`jenkins_scm_poll_enabled=true`, cf. section « Declenchement »).

## Pourquoi c'est manuel (et pourquoi ca le restera)

Le role ne peut pas faire ces etapes a ta place, et ce n'est pas un manque :

| A faire a la main | Pourquoi ce n'est pas dans le role |
|---|---|
| Generer la paire de cles | Une cle privee est un **secret**. Elle n'a rien a faire dans ce depot, ni dans un rendu Ansible, ni dans un log de playbook. |
| Declarer la cle publique sur GitHub | Ecriture via l'API authentifiee de GitHub — exigerait un **token GitHub** cote Ansible, avant tout cablage Vault. |
| Deposer la cle privee dans Jenkins | Ecriture via l'API authentifiee de Jenkins — meme probleme (c'est deja la raison pour laquelle le role ne cree pas les jobs). |

Ce que le role fait, lui : rendre les `config.xml` qui **referencent** le
credential par son **ID** (un nom, pas un secret) et retirer du fragment tout ce
qui n'a plus lieu d'etre en mode remote.

## 1. Generer la paire de cles (sur l'hote, HORS du depot)

```bash
mkdir -p ~/.ssh
ssh-keygen -t ed25519 -C "travel-plan-jenkins-deploy" \
  -f ~/.ssh/travel-plan-jenkins-deploy -N ""
# -> ~/.ssh/travel-plan-jenkins-deploy      (PRIVEE : ne JAMAIS la commiter)
# -> ~/.ssh/travel-plan-jenkins-deploy.pub  (publique)
```

`ed25519` plutot que RSA : plus courte, supportee par GitHub depuis longtemps.
`-N ""` (sans passphrase) est **deliberé** : Jenkins doit pouvoir cloner sans
interaction ; une passphrase devrait de toute facon etre stockee a cote de la
cle, ce qui ne protegerait rien de plus ici. La protection reelle, c'est le
**perimetre** de la cle : un seul depot, **lecture seule** (etape 2).

## 2. Declarer la cle publique comme **deploy key LECTURE SEULE** sur GitHub

Dans le navigateur, sur le depot
`https://github.com/FredericTischler/travel-plan` :

*Settings → Deploy keys → **Add deploy key***

| Champ | Valeur |
|---|---|
| Title | `travel-plan-jenkins` (libre, sert a le revoquer plus tard) |
| Key | le contenu **integral** de `~/.ssh/travel-plan-jenkins-deploy.pub` (`cat` puis copier, une seule ligne `ssh-ed25519 AAAA... travel-plan-jenkins-deploy`) |
| **Allow write access** | **NE PAS COCHER** |

> **Deploy key plutot qu'un PAT** : une deploy key est limitee a **ce depot**, un
> PAT porte les droits du **compte**. Si la machine de CI est compromise, une
> deploy key en lecture seule ne permet que de relire un depot qu'on a deja en
> local — rien a pousser, rien a supprimer, aucun autre depot. La case *Allow
> write access* laissee decochee est le point le plus important de cette page.

## 3. Deposer la cle **privee** dans le credential store de Jenkins

Jenkins doit tourner (`docker compose --profile ci up -d jenkins`), puis
`https://jenkins.localhost/` :

*Manage Jenkins → Credentials → System → Global credentials → **Add Credentials***

| Champ | Valeur |
|---|---|
| Kind | **SSH Username with private key** |
| Scope | Global |
| ID | **`github-deploy-key`** — c'est la valeur a repasser en `-e jenkins_scm_credential_id` (convention proposee ; tout autre ID marche, il faut juste que les deux coincident) |
| Description | `Deploy key GitHub travel-plan (lecture seule)` |
| Username | **`git`** — impose par GitHub pour un remote `git@github.com:...` |
| Private Key | *Enter directly* → coller le contenu **integral** de `~/.ssh/travel-plan-jenkins-deploy`, en-tete `-----BEGIN OPENSSH PRIVATE KEY-----` et pied `-----END OPENSSH PRIVATE KEY-----` **compris**, avec le saut de ligne final |
| Passphrase | vide (cf. etape 1) |

> Un `Username` autre que `git` donne un `Permission denied (publickey)`
> parfaitement trompeur : la cle est bonne, c'est l'utilisateur SSH qui ne l'est
> pas. C'est l'erreur la plus courante de cette page.

## 4. Autoriser la cle d'hote de `github.com` (le piege qui coute une heure)

Le git-plugin verifie par defaut la cle d'hote du serveur SSH contre le
`known_hosts` de Jenkins — **vide** dans un `JENKINS_HOME` neuf. Sans cette
etape, le checkout echoue sur
`Host key verification failed` / `No ED25519 host key is known for github.com`,
alors que la cle de deploiement est parfaitement valide.

*Manage Jenkins → Security → **Git Host Key Verification Configuration*** →
strategie **`Known hosts file`** (recommandee), puis alimenter le fichier :

```bash
docker exec travel-plan-jenkins bash -c '
mkdir -p /var/jenkins_home/.ssh
ssh-keyscan -t rsa,ecdsa,ed25519 github.com >> /var/jenkins_home/.ssh/known_hosts
sort -u -o /var/jenkins_home/.ssh/known_hosts /var/jenkins_home/.ssh/known_hosts
wc -l < /var/jenkins_home/.ssh/known_hosts'
```

Le fichier vit dans le **volume nomme** : cette etape ne se rejoue pas.
L'alternative `Accept first connection` demande un clic de moins mais accepte
aveuglement la premiere cle presentee — a ne choisir qu'en connaissance de cause.

## 5. Rejouer le role en mode B — **la commande exacte**

```bash
cd /chemin/vers/le/depot/travel-plan
source .venv-ansible/bin/activate

ansible-playbook ansible/roles/jenkins/test-local.yml -K \
  -e jenkins_scm_url=git@github.com:FredericTischler/travel-plan.git \
  -e jenkins_scm_credential_id=github-deploy-key
```

`jenkins_scm_repo_host_path` n'est **plus** a fournir : la garde fail-fast qui
l'exigeait ne s'applique qu'au mode `file://`. Si l'URL est en SSH **sans**
`jenkins_scm_credential_id`, le role **echoue tout de suite**, avec le message
qui dit quoi faire — plutot que de laisser decouvrir un
`Permission denied (publickey)` au premier build.

Ce que ce run change dans les rendus :

- les 3 `config.xml` pointent sur `git@github.com:...` + `<credentialsId>` ;
- `compose.jenkins.yml` **perd** le bind-mount `/srv/travel-plan-repo:ro` et
  **perd** `-Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true` (inutile hors
  `file://`, donc le garde-fou du git-plugin se retablit tout seul).

Puis reassembler et **recreer** le conteneur (le fragment a change) :

```bash
ansible-playbook ansible/roles/compose-assembly/test-local.yml -K \
  -e assembly_jenkins_enabled=true \
  -e assembly_identity_fragment=/chemin/vers/le/depot/travel-plan/services/identity-service/docker-compose.identity.yml

cd /opt/travel-plan && docker compose --profile ci up -d jenkins
```

## 6. Mettre a jour les jobs **existants** (pas `createItem`)

Les jobs existent deja : c'est un **update** de leur `config.xml`, pas une
creation (`createItem` repondrait `400 A job already exists`).

```bash
for JOB in identity-service-test payment-service-test travel-service-test; do
  docker cp "/opt/travel-plan/jenkins/job-${JOB}-config.xml" \
            "travel-plan-jenkins:/tmp/job-${JOB}.xml"

  docker exec -e JOB="$JOB" travel-plan-jenkins bash -c '
  PW=$(cat /var/jenkins_home/secrets/initialAdminPassword)
  CRUMB=$(curl -s -u "admin:$PW" -c /tmp/ck \
    "http://localhost:8080/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")
  curl -s -o /dev/null -w "update $JOB: %{http_code}\n" -u "admin:$PW" -b /tmp/ck -H "$CRUMB" \
    -H "Content-Type: application/xml" --data-binary @/tmp/job-$JOB.xml \
    "http://localhost:8080/job/$JOB/config.xml"
  '
done
# attendu : update <job>: 200  (x3)
```

> Si le mot de passe admin a ete change (etape 3 de la sequence principale),
> remplacer `$PW` par le mot de passe reel — jamais en clair dans un fichier du
> depot.

## 7. Verifier — et ce qui reste a prouver

Declencher **un** build a la main (etape 5 de la sequence principale) et lire le
debut du log : il doit montrer un `git fetch` sur `git@github.com:...` et non sur
`file:///srv/travel-plan-repo`.

Symptomes et causes, dans l'ordre de frequence :

| Message | Cause |
|---|---|
| `Permission denied (publickey)` | `Username` != `git` (etape 3), ou cle publique non declaree sur GitHub (etape 2) |
| `Host key verification failed` | etape 4 sautee |
| `Jenkinsfile not found` | le Jenkinsfile n'est pas **pousse** sur la branche `jenkins_scm_branch` (en mode B, un commit local ne suffit plus) |

> **Cette etape 7 n'a PAS ete executee lors de la mise en place du mode B** :
> elle exige la cle de deploiement, que l'operateur est le seul a detenir. Ce qui
> est prouve a ce stade : le **rendu** des deux modes (Molecule, mode A **et**
> mode B) et l'idempotence. Le checkout GitHub reel se valide au premier build.

## 8. Revenir au mode A

Rejouer le role **sans** les deux `-e` (le defaut est le mode A), avec de nouveau
`-e jenkins_scm_repo_host_path=...`, puis refaire les etapes 5 (reassemblage) et
6 (update des jobs). Le credential Jenkins peut rester en place : il n'est plus
reference par aucun job. Pour le revoquer vraiment, supprimer la **deploy key**
cote GitHub (*Settings → Deploy keys*) — c'est la seule action qui coupe
reellement l'acces.
