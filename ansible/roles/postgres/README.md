# role: postgres

Provisionne une instance PostgreSQL pour Travel-Plan et **rend un fragment de
service Compose** (Ansible provisionne, docker-compose orchestre). La base est
**interne** (`data-net`), **jamais exposee a l'hote**, avec un garde-fou memoire
(`mem_limit: 256m`, cf. `docs/architecture-decisions.md` §4).

## Ce que le role fait (plomberie d'infra UNIQUEMENT)
- Rend `compose.postgres.yml` (image **taguee** `postgres:17.5-bookworm`, `data-net`,
  `mem_limit`, healthcheck, **aucun port publie**).
- Cree **2 bases vides** : `identity_db`, `payment_db`.
- Cree **2 comptes a droits limites** : `identity_user`, `payment_user`
  (`NOSUPERUSER,NOCREATEDB,NOCREATEROLE,LOGIN`), chacun **owner de SA seule base**.
- **Cloisonnement moindre privilege** : `CONNECT` revoque a `PUBLIC` puis accorde
  uniquement au proprietaire => `payment_user` ne peut PAS joindre `identity_db` et
  reciproquement.

## Ce que le role NE fait PAS
Aucune table / colonne / index / contrainte / extension metier : cela releve de
**Flyway cote services Spring**, plus tard. `postgres_extensions` est **vide** par defaut.

## Secrets
Mots de passe **parametres** dans `defaults/main.yml` (valeurs de dev `changeme_*`).
En prod ils sont **injectes depuis Vault** ; aucun secret reel n'est commite.

## Test (idempotence prouvee)
Scenario Molecule (driver docker, conteneurs freres — Linux natif, pas de DinD) :

Le scenario est **autonome** : il place lui-meme le `roles_path` via
`ansible.env.ANSIBLE_ROLES_PATH` dans `molecule.yml`, derive de
`${MOLECULE_SCENARIO_DIRECTORY}` (interpole par Molecule, chemin absolu).
Aucun `export ANSIBLE_ROLES_PATH` shell n'est requis ; le test passe quel que
soit le repertoire de lancement.

```bash
source .venv-ansible/bin/activate
cd ansible/roles/postgres
molecule test
```

La sequence (`create` -> `prepare` -> `converge` -> `idempotence` -> `verify`)
exige `changed=0` au 2e converge et verifie : existence des 2 bases / 2 comptes,
aucun compte superuser, cloisonnement (connexions croisees REFUSEES, legitimes OK),
conformite du fragment Compose.