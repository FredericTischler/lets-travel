# role: vault

Provisionne **Vault (dev mode)** pour Travel-Plan. Modele operatoire identique a
`postgres` : Ansible **provisionne** et **rend le fragment Compose** ;
docker-compose **orchestre** (profils core/full/ci).

## Ce que fait le role

1. **Rend un fragment de service Compose** (`compose.vault.yml`) : image epinglee
   `hashicorp/vault:1.18.3`, dev mode (`server -dev`), `mem_limit` 96m, reseau
   `data-net`, **aucun port publie vers l'hote** (seul Traefik expose, cf.
   `docs/architecture-decisions.md` §4). Root token dev FIXE via
   `VAULT_DEV_ROOT_TOKEN_ID`, reference d'une variable d'env (jamais en clair).
2. **Verifie** le moteur **KV v2** sur `secret/` (deja monte en dev mode ->
   check `sys/mounts`, pas d'enable aveugle).
3. **Ecrit les secrets runtime** (KV v2, read-before-write idempotent) :
   `secret/identity/db`, `secret/payment/db`, `secret/payment/stripe`,
   `secret/payment/paypal`, `secret/travel/db`. Valeurs = placeholders
   `changeme_*`, injectees par Vault/ansible-vault en prod.
4. **Pose les policies par service** (moindre privilege, §5) :
   `identity-policy`, `payment-policy`, `travel-policy`. Capacites visant
   `secret/data/<svc>/*` ET `secret/metadata/<svc>/*`. Aucune ne lit le
   perimetre d'une autre. Ecriture conditionnee a un diff HCL normalise
   (idempotent).

## Reconciliation dev mode <-> §5

Vault dev mode = **auto-unsealed, en memoire, non persistant**. Donc : aucune
logique d'unseal/persistance ; KV v2 deja monte (verifie, pas enable) ; la
"nuance bootstrap §5" se reduit a *le root token dev ne se commite pas en clair*
(source d'une variable : ansible-vault en prod, fixture en Molecule).

## Idempotence

- **Secrets KV v2** : `community.hashi_vault.vault_kv2_write` avec
  `read_before_write: true` -> ne versionne que si `data` differe (2e run :
  `changed=0`).
- **Policies** : `vault_read` de la policy existante + normalisation des blancs
  (`regex_replace('\s+','')`) + `vault_write` seulement si l'HCL differe.

## Test

```bash
source /home/kheesi/Bureau/Zone01/travel-plan/.venv-ansible/bin/activate
cd /home/kheesi/Bureau/Zone01/travel-plan/ansible/roles/vault
molecule test
```

Scenario Molecule : plateforme = conteneur `hashicorp/vault:1.18.3` en dev mode
(PID1), port de **test** `8200:8200` publie cote plateforme ; les modules
`community.hashi_vault` tournent **depuis le controleur** (venv + `hvac`) en
visant `localhost:8200`. Ce port de test est distinct du fragment Compose rendu
(qui n'a aucun port hote). `verify.yml` assert : KV v2 v2 sur `secret/`, les 5
chemins et leurs cles, les 3 policies cloisonnees, et le **refus** (token
`identity-policy` : lit `identity/db`, refuse `payment/db`).