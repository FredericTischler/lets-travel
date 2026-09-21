# Role `app-secrets`

Proprietaire **unique** du fichier `/opt/travel-plan/.env` consomme par Docker Compose.

## Responsabilite

Lire les secrets runtime dans Vault (KV v2, mount `secret`) et les materialiser dans
un fichier `.env` unique, auto-charge par Compose depuis le repertoire du
`docker-compose.yml` top-level.

| Cle du `.env` | Chemin Vault | Cle Vault |
| --- | --- | --- |
| `POSTGRES_SUPERUSER_PASSWORD` | `secret/infra/postgres-superuser` | `password` |
| `IDENTITY_DB_PASSWORD` | `secret/identity/db` | `password` |
| `BOOTSTRAP_ADMIN_EMAIL` | `secret/identity/bootstrap-admin` | `email` |
| `BOOTSTRAP_ADMIN_PASSWORD` | `secret/identity/bootstrap-admin` | `password` |
| `PAYMENT_DB_PASSWORD` | `secret/payment/db` | `password` |
| `STRIPE_API_KEY` | `secret/payment/stripe` | `api_key` |
| `STRIPE_SECRET_KEY` | `secret/payment/stripe` | `secret_key` |
| `STRIPE_WEBHOOK_SECRET` | `secret/payment/stripe` | `webhook_secret` |
| `PAYPAL_CLIENT_ID` | `secret/payment/paypal` | `client_id` |
| `PAYPAL_CLIENT_SECRET` | `secret/payment/paypal` | `client_secret` |
| `NEO4J_PASSWORD` | `secret/travel/db` | `password` |
| `JWT_SIGNING_KEY` | `secret/shared/jwt` | `signing_key` |

## Pourquoi un role transverse plutot qu'un role par service

`.env` est un fichier **unique**, rendu integralement par un `template`. Deux roles
(par exemple `payment` et `travel`) qui le templateraient chacun s'ecraseraient
mutuellement a chaque run : `changed` systematique, idempotence Ansible violee.
Un fichier, un proprietaire.

Ce role reprend la responsabilite du bloc `compose_fragment` qui vivait dans
`roles/postgres` (lecture `infra/postgres-superuser` + rendu du `.env`). Le role
`postgres` conserve son bloc `provision`, qui relit Vault pour ses propres besoins
de provisioning (comptes/bases) — responsabilite distincte, pas une duplication.

## Flag Molecule-safe

`app_secrets_vault_read: false` par defaut : le role est alors un **no-op integral**
(aucune lecture Vault, aucun fichier ecrit). Meme pattern que `postgres_vault_read`.
Pour un run reel : `-e app_secrets_vault_read=true`.

## Test

```bash
cd ansible/roles/app-secrets && molecule test
```

Le scenario couvre la garantie Molecule-safe (rien d'ecrit avec le flag a `false`)
et l'idempotence. Il **ne couvre pas** le chemin Vault : celui-ci exige un Vault
provisionne et des secrets reels.

## Hors perimetre

Aucun mecanisme d'authentification Vault cote service (AppRole / Vault Agent) :
c'est Ansible, avec son token, qui lit les secrets et les pousse dans le `.env`.
