# Rôle `sonarqube`

Provisionne le serveur d'analyse qualité **SonarQube Community Edition**
(`sonarqube:26.9.0.129388-community`) et **sa base Postgres dédiée**, sous forme
d'un fragment Compose du profil `ci`.

Modèle opératoire identique aux autres rôles du projet :
**Ansible provisionne et rend des fichiers, docker-compose orchestre.**
Le rôle ne démarre aucun conteneur, n'appelle jamais l'API de SonarQube et
n'écrit aucun secret.

Ce que le rôle fait exactement, et pourquoi chaque valeur a été choisie, est
documenté ligne à ligne dans `defaults/main.yml`, `tasks/main.yml` et
`templates/compose.sonarqube.yml.j2`. Ce README ne les répète pas : il couvre
les **procédures manuelles** qui restent à la charge de l'opérateur.

---

## 1. Ce que le rôle ne fait pas — et qui doit donc être fait à la main

Deux choses ne peuvent pas être provisionnées par ce rôle, pour une seule et
même raison : **elles exigent un credential SonarQube**, c'est-à-dire un compte
qui n'existe qu'après le premier démarrage du serveur. Le contrat du projet
interdit à la fois le credential en clair et le placeholder « temporaire ». On
s'arrête donc proprement et on documente.

| À faire à la main | Pourquoi ce n'est pas dans le rôle |
|---|---|
| Le changement du mot de passe `admin` au premier démarrage | Écriture via l'API authentifiée de SonarQube. |
| La génération du **token d'analyse** et son dépôt dans Jenkins | Idem, plus : un token est un secret, il n'a rien à faire dans ce dépôt. |

En revanche, deux points qui figuraient ici comme « à faire à la main » ne le
sont **plus** : le mot de passe de la base dédiée (§2) et l'inclusion du fragment
dans le Compose assemblé (§7bis) sont désormais câblés.

---

## 2. Mot de passe de la base dédiée — câblé, plus rien à faire à la main

Le fragment rendu ne contient **que la référence** `${SONARQUBE_DB_PASSWORD}` ;
la valeur est résolue par Compose depuis `/opt/travel-plan/.env`, dont le rôle
`app-secrets` est le propriétaire **unique** (deux rôles templatant le même
fichier s'écraseraient à chaque run — idempotence violée).

La chaîne est celle de **tous** les autres secrets du projet, sans exception :

```
vault/defaults/main.yml   secret/sonarqube/db  (clé `password`)
        │                 déclaré dans vault_secrets, écrit en read-before-write
        ▼
app-secrets               lecture vault_kv2_get -> fact -> ligne du .env
        │                 (app_secrets_vault_path_sonarqube_db)
        ▼
/opt/travel-plan/.env     SONARQUBE_DB_PASSWORD=...
        ▼
Compose au `up`           POSTGRES_PASSWORD (la base) ET SONAR_JDBC_PASSWORD
                          (le serveur) — une seule clé, deux consommateurs,
                          donc jamais de désynchronisation possible.
```

Comme partout ailleurs, `defaults/main.yml` du rôle `vault` ne porte qu'un
**placeholder** `changeme_sonarqube_db` : la valeur réelle se fournit en
surcharge (`-e`, ou fichier chiffré `ansible-vault`), jamais dans le dépôt.

```bash
# provisionner le secret puis régénérer le .env
ansible-playbook ansible/roles/vault/test-local.yml --tags provision \
  -e vault_addr=http://<ip-conteneur-vault>:8200
ansible-playbook ansible/roles/app-secrets/test-local.yml \
  -e app_secrets_vault_read=true -e vault_addr=http://<ip-conteneur-vault>:8200
```

Tant que `SONARQUBE_DB_PASSWORD` est absent du `.env`, `docker compose --profile
ci up -d` démarre une Postgres **sans mot de passe défini** et SonarQube échoue
sa migration de schéma. C'est un échec bruyant et immédiat, pas un piège silencieux.

> **Piège si la base tourne déjà.** `POSTGRES_PASSWORD` n'est appliqué qu'au
> **premier** `initdb`. Changer la valeur du secret sur un volume
> `sonarqube-db-data` déjà initialisé ne change **rien** côté base : SonarQube se
> connectera alors avec la nouvelle valeur contre une base qui a gardé
> l'ancienne, et échouera à l'authentification. Deux issues : supprimer le volume
> (on perd l'historique d'analyses), ou aligner la base à la main —
> `docker exec travel-plan-sonarqube-db psql -U sonar -d sonar -c "ALTER USER sonar WITH PASSWORD '<nouvelle valeur>'"`
> puis recréer le conteneur SonarQube.

---

## 3. Premier démarrage : mot de passe admin

SonarQube crée au premier démarrage un compte `admin` dont le mot de passe
initial est `admin` (comportement documenté officiellement par SonarSource, et
identique sur cette version — vérifié empiriquement sur cette instance).

**Il faut le changer immédiatement.** Tant qu'il ne l'est pas, n'importe qui
ayant accès à `https://sonarqube.localhost` est administrateur du serveur.

```bash
# depuis l'hôte, via le conteneur (aucun port n'est publié)
docker exec travel-plan-sonarqube curl -s -u admin:admin \
  -X POST "http://localhost:9000/api/v2/users-management/users/<uuid>/change-password" ...
# ou, plus simple, via l'UI : https://sonarqube.localhost
```

> **Piège réel, rencontré sur cette instance.** Ce mot de passe n'est stocké
> qu'en **haché** (PBKDF2) dans la table `users`, et les tokens le sont aussi
> (table `user_tokens`). Si vous le perdez, il n'est **pas** récupérable, et le
> reset SQL documenté pour les versions 9.x/10.x ne fonctionne plus sur la
> 26.x. Le seul recours propre est de repartir d'une base et d'un volume
> `data` vierges. **Notez-le dans votre gestionnaire de mots de passe au moment
> où vous le changez.**

---

## 4. Token d'analyse

### Génération

```bash
docker exec travel-plan-sonarqube curl -s -u admin:<mot-de-passe-admin> \
  -X POST "http://localhost:9000/api/user_tokens/generate?name=travel-plan-ci&type=GLOBAL_ANALYSIS_TOKEN"
```

Réponse :

```json
{"login":"admin","name":"travel-plan-ci","token":"sqa_...","type":"GLOBAL_ANALYSIS_TOKEN"}
```

`type=GLOBAL_ANALYSIS_TOKEN` et pas `USER_TOKEN` : un token d'analyse global ne
peut que **pousser des rapports**. Il ne permet ni de lire le code source, ni
d'administrer le serveur, ni d'agir au nom de l'utilisateur. C'est le moindre
privilège pour ce que le pipeline a réellement besoin de faire.

**La valeur du token n'est affichée qu'une fois.** Elle n'est ensuite stockée
que hachée côté serveur.

### Dépôt dans Jenkins

Le token ne doit **jamais** être écrit dans le dépôt, dans un Jenkinsfile, ni
dans un fichier rendu par Ansible. Il se dépose manuellement dans le
credential store de Jenkins :

*Manage Jenkins → Credentials → System → Global → Add Credentials*

| Champ | Valeur |
|---|---|
| Kind | Secret text |
| Secret | la valeur `sqa_...` |
| ID | `sonarqube-token` — **cet ID exact**, il est référencé par les trois Jenkinsfiles |

Puis, *Manage Jenkins → System → Global properties → Environment variables* :

| Nom | Valeur |
|---|---|
| `SONAR_HOST_URL` | `http://sonarqube:9000` |

> `http://sonarqube:9000` et **pas** `https://sonarqube.localhost` : Jenkins et
> SonarQube partagent `backend-net`, ils se joignent par nom de service. Passer
> par la route Traefik ferait traverser au scanner un certificat auto-signé
> absent du truststore de sa JVM — échec handshake TLS, pour rien.

Les deux stages Sonar des Jenkinsfiles sont gardés par
`when { expression { env.SONAR_HOST_URL?.trim() } }` : tant que la variable
n'est pas posée, les pipelines tournent sans analyse au lieu d'échouer. C'est
volontaire — le serveur SonarQube appartient au profil `ci` et n'est pas
toujours démarré.

---

## 5. Lancer une analyse à la main (hors Jenkins)

Utile pour vérifier l'installation sans démarrer Jenkins. Le scanner doit être
**sur `backend-net`**, car aucun port n'est publié :

```bash
docker run --rm --network backend-net \
  -v "$PWD/services/identity-service":/build -w /build \
  -e SONAR_TOKEN="sqa_..." \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -B --no-transfer-progress -DskipTests verify sonar:sonar \
    -Dsonar.host.url=http://sonarqube:9000 \
    -Dsonar.projectKey=travel-plan-identity-service
```

Vérifier ensuite le quality gate — **le scanner rend la main avant que le gate
soit calculé** (le Compute Engine travaille de façon asynchrone), donc un
`BUILD SUCCESS` ne dit rien à lui seul :

```bash
docker exec travel-plan-sonarqube curl -s -u admin:<mdp> \
  "http://localhost:9000/api/qualitygates/project_status?projectKey=travel-plan-identity-service"
```

### Résultat réellement obtenu sur `identity-service`

Analyse du 2026-09-15, scanner `sonar-maven-plugin:5.8.0.7211`, serveur
`26.9.0.129388-community` :

```
tâche Compute Engine : SUCCESS
quality gate          : OK   (caycStatus: compliant)
ncloc                 : 825
bugs                  : 0
vulnerabilities       : 0
security_hotspots     : 0
code_smells           : 7    (4 MAJOR, 3 INFO)
duplicated_lines      : 0.0 %
coverage              : 0.0 %
```

Deux précisions honnêtes sur ce « OK » :

1. **`conditions` est une liste vide.** Le quality gate « Sonar way » ne porte
   que sur le **new code**. À la toute première analyse d'un projet il n'existe
   pas encore de période de référence, donc aucune condition n'est évaluable :
   le gate est `OK` par absence de condition, pas parce que le code a été jugé
   bon. À partir de la deuxième analyse, les conditions apparaissent réellement.
2. **`coverage: 0.0 %`** parce que `jacoco-maven-plugin` n'est branché sur aucun
   des trois modules et que cette analyse a tourné avec `-DskipTests`. La
   condition « coverage on new code ≥ 80 % » du Sonar way fera donc **échouer**
   le gate dès la deuxième analyse tant que JaCoCo n'est pas ajouté. C'est une
   limite connue et assumée de cet incrément, pas un test truqué.

---

## 6. Inclusion dans le Compose assemblé — fait

`/opt/travel-plan/docker-compose.yml`, rendu par le rôle **`compose-assembly`**,
inclut désormais `sonarqube/compose.sonarqube.yml` derrière le flag
`assembly_sonarqube_enabled` (`false` par défaut, même raisonnement que
`assembly_jenkins_enabled` : un `include:` est résolu **quel que soit** le
`--profile`, donc inclure en dur un fragment absent du disque casserait aussi le
rendu de `core` et de `full`).

Concrètement, côté `compose-assembly` :

- `include:` de `sonarqube/compose.sonarqube.yml` (chemin **relatif** — le rôle
  rend son fragment sous `assembly_compose_dir`, rien à fournir en `-e`) ;
- merge `profiles: [ci]` sur `sonarqube` **et** `sonarqube-db` (sans profil sur
  la base, `--profile ci` monterait le serveur seul et sa migration de schéma
  échouerait) ;
- `ci` présent dans la liste de profils de `traefik`, sans quoi l'UI serait
  injoignable.

```bash
ansible-playbook ansible/roles/compose-assembly/test-local.yml -K \
  -e assembly_sonarqube_enabled=true [...autres flags...]

# plus aucun -f manuel :
docker compose --profile ci up -d
```

**Vérifié réellement** : `docker compose --profile ci config --services` rend
`jenkins sonarqube sonarqube-db traefik`, et le `up` correspondant démarre la
base puis le serveur (`healthy`), UI joignable en HTTP 200 sur
`https://sonarqube.localhost`.

---

## 7. Tests

```bash
# application locale (écrit le fragment ET pose des sysctl sur l'hôte)
ansible-playbook ansible/roles/sonarqube/test-local.yml -K

# idempotence + assertions (scénario Molecule complet)
cd ansible/roles/sonarqube && molecule test
```

Le scénario Molecule vérifie le rendu du fragment et l'idempotence. Il joue le
rôle avec `sonarqube_manage_sysctl=false` : `/proc/sys` n'est pas inscriptible
depuis un conteneur non privilégié.
