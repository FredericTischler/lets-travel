# role: docker-host

Prépare l'**hôte** Ubuntu : installe Docker CE + le plugin Compose v2 (`docker compose`)
depuis le dépôt APT officiel Docker (format deb822). Cible : Ubuntu (jammy/noble), Linux natif.

## Périmètre

Ce rôle fait **uniquement** :

1. Pré-check non destructif des paquets distro conflictuels (`docker.io`, `docker-compose`,
   `docker-compose-v2`, `docker-doc`, `podman-docker`, `containerd`, `runc`). S'il en trouve,
   le rôle **échoue** et demande un retrait manuel — il ne supprime jamais rien lui-même.
2. Clé GPG Docker (`/etc/apt/keyrings/docker.asc`) + dépôt deb822 + install des 5 paquets
   `docker-ce`, `docker-ce-cli`, `containerd.io`, `docker-buildx-plugin`, `docker-compose-plugin`.
3. Démon Docker `enabled` + `started` (systemd).
4. Ajout des utilisateurs (`docker_host_users`) au groupe `docker`.

**Hors périmètre** (volontairement non traité ici) :

- Configuration du démon / `daemon.json` / logging → rôle `observability`.
- Création des réseaux Docker (edge / backend / data) → rôle d'assemblage compose.

## Variables (defaults)

| Variable | Défaut | Rôle |
|---|---|---|
| `docker_host_users` | `["{{ lookup('env','USER') }}"]` | Utilisateurs ajoutés au groupe `docker` |
| `docker_host_packages` | 5 paquets Docker CE | Paquets installés |
| `docker_host_conflicting_packages` | liste distro | Paquets bloquants détectés |

`lookup('env','USER')` est évalué sur le **contrôleur** : sous `ansible-playbook -K` (sudo),
`USER` reste l'utilisateur réel (pas `root`), donc c'est bien lui qui est ajouté au groupe `docker`.

## Idempotence

Modules natifs uniquement (`package_facts`, `apt`, `get_url`, `file`, `deb822_repository`,
`systemd_service`, `user`). Aucun `shell`/`command`. Un 2e run rapporte `changed=0`.

## Test / application

Pas de scénario Molecule (tester l'install de Docker exigerait DinD, écarté). Validation =
application réelle par l'utilisateur + checklist + 2e run pour l'idempotence.

```bash
# Vérif sans effet de bord :
ansible-playbook ansible/roles/docker-host/test-local.yml --syntax-check

# Application réelle (installe Docker) :
ansible-playbook ansible/roles/docker-host/test-local.yml -K
```

> Piège : l'ajout au groupe `docker` exige un **re-login** (ou `newgrp docker`) pour
> prendre effet. Sans ça, `docker` sans `sudo` échouera.