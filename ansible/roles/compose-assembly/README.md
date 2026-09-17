# Rôle `compose-assembly` — le point d'entrée Compose

Ce rôle ne provisionne aucun service. Il fait deux choses, et seulement deux :

1. **créer les trois réseaux Docker partagés** (`edge-net`, `backend-net`,
   `data-net`) — Ansible en est le propriétaire, les fragments les référencent en
   `external: true` ;
2. **rendre `/opt/travel-plan/docker-compose.yml`**, qui assemble par `include:`
   les fragments rendus par tous les autres rôles et applique les **profils**.

Conséquence assumée : `docker compose down` ne supprime **pas** les réseaux. Leur
cycle de vie appartient exclusivement à Ansible.

---

## 1. Profils

| Profil | Services |
|---|---|
| `core` | `postgres`, `vault`, `traefik` |
| `full` | `core` + `neo4j` + les services applicatifs activés |
| `ci` | `jenkins`, `sonarqube`, `sonarqube-db`, `traefik` — à la demande |
| `observability` | `loki`, `promtail`, `grafana`, `traefik` — **additif** |

`observability` se combine : `--profile full --profile observability`. Il observe
la stack applicative, il n'a donc d'intérêt que si elle tourne. `ci` au contraire
ne cohabite pas avec elle sur 8 Go (docs §8).

### La règle qui casse silencieusement quand on l'oublie

**Tout profil contenant un service routé par Traefik doit figurer dans la liste
de profils de `traefik`** — sinon `--profile <ce profil>` monte le service sans
la gateway qui l'expose. Le conteneur démarre, passe `healthy`, et personne ne
peut l'atteindre : aucun message d'erreur nulle part.

La liste est **calculée** dans le template à partir des flags :
`[core, full]`, `+ ci` si `assembly_jenkins_enabled` ou
`assembly_sonarqube_enabled`, `+ observability` si
`assembly_observability_enabled`. Ajouter un service routé dans un nouveau profil
suppose d'étendre ce calcul.

---

## 2. Flags d'inclusion

Les `include:` Compose sont résolus **quel que soit le `--profile`** : un chemin
invalide casse le rendu de *tous* les profils, pas seulement du sien. D'où un
flag par fragment optionnel, plutôt qu'un include en dur.

| Flag | Défaut | Fragment | Nature du chemin |
|---|---|---|---|
| — | inclus | `postgres`, `vault`, `neo4j`, `traefik` | relatif |
| `assembly_identity_enabled` | `true` | `assembly_identity_fragment` | **absolu**, à fournir en `-e` |
| `assembly_payment_enabled` | `false` | `assembly_payment_fragment` | **absolu**, à fournir en `-e` |
| `assembly_travel_enabled` | `false` | `assembly_travel_fragment` | **absolu**, à fournir en `-e` |
| `assembly_jenkins_enabled` | `false` | `jenkins/compose.jenkins.yml` | relatif |
| `assembly_sonarqube_enabled` | `false` | `sonarqube/compose.sonarqube.yml` | relatif |
| `assembly_observability_enabled` | `false` | `observability/compose.observability.yml` | relatif |

**Deux natures de chemin, deux raisons.** Les trois services applicatifs
*buildent depuis les sources* : leur fragment reste co-localisé avec le
`Dockerfile` et `src/`, donc référencé par chemin **absolu**, variable d'une
machine à l'autre. Tous les autres sont rendus par un rôle Ansible sous
`assembly_compose_dir` : chemin **relatif**, rien à fournir.

`jenkins`, `sonarqube` et `observability` sont à `false` par défaut pour la même
raison mécanique (fragment potentiellement absent du disque), doublée d'une
raison de RAM : ni la CI ni le stack de logs ne tournent au quotidien.

---

## 3. Application

Ce rôle se joue **en dernier** : il assemble des fragments déjà rendus.

```bash
ansible-playbook ansible/roles/compose-assembly/test-local.yml -K \
  -e assembly_identity_fragment=/abs/.../services/identity-service/docker-compose.identity.yml \
  -e assembly_payment_enabled=true \
  -e assembly_payment_fragment=/abs/.../services/payment-service/docker-compose.payment.yml \
  -e assembly_travel_enabled=true \
  -e assembly_travel_fragment=/abs/.../services/travel-service/docker-compose.travel.yml \
  -e assembly_jenkins_enabled=true \
  -e assembly_sonarqube_enabled=true \
  -e assembly_observability_enabled=true
```

### Piège ownership si `-K` est omis une fois

`-K` (become) est **nécessaire** pour que le rôle rende `/opt/travel-plan/docker-compose.yml` en
tant que `root` (comme les autres fichiers sous `/opt/travel-plan`). Si le rôle est un jour rejoué
avec `-e ansible_become=false` (faute de mot de passe sudo disponible, par exemple en session
automatisée), Ansible rend quand même le fichier — mais avec l'utilisateur courant comme
propriétaire (`kheesi:kheesi` par exemple), pas `root:root`. Rien ne casse dans l'immédiat (Compose
lit le fichier normalement), mais au **prochain run avec `-K`**, Ansible restaure l'ownership
`root:root` attendu, ce qui retire l'accès en écriture direct au fichier pour l'utilisateur non-root
qui l'avait édité entre-temps. Si tu vois un fichier `docker-compose.yml` qui n'appartient pas à
`root`, c'est le signe qu'un run précédent a été fait sans `-K` — rejoue avec `-K` pour revenir à
l'état attendu plutôt que de corriger l'ownership à la main.

Puis, **sans aucun `-f`** :

```bash
cd /opt/travel-plan
docker compose --profile full up -d                              # applicatif
docker compose --profile full --profile observability up -d      # + logs
docker compose --profile ci up -d                                # CI seule
```

---

## 4. Tests

**Pas de scénario Molecule**, et c'est explicite : ce rôle ne rend qu'un fichier
et crée des réseaux Docker ; l'assertion qui compte (« le fichier assemblé est
valide ») exige les fragments des *autres* rôles présents sur le disque, ce
qu'un conteneur Molecule isolé n'a pas. La validation est donc réelle :

```bash
# 1. rendu
ansible-playbook ansible/roles/compose-assembly/test-local.yml -K -e ...
# 2. un second run doit rapporter changed=0
# 3. le fichier assemblé doit parser
cd /opt/travel-plan && docker compose --profile <p> config --services
```
