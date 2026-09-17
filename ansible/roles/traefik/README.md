# role: traefik

La **gateway** de Travel-Plan : point d'entree unique, terminaison TLS au bord,
provider Docker. Ansible **provisionne** (certs + configs) et **rend un fragment
de service Compose** ; docker-compose **orchestre**. C'est le **SEUL** role du
projet a **publier un port** (443, sur `edge-net`) et le seul a chevaucher
`edge-net` ET `backend-net` (cf. `docs/architecture-decisions.md` §4/§6).

## Ce que le role fait (conteneur gateway UNIQUEMENT)
- Rend `compose.traefik.yml` : image **taguee** `traefik:v3.7.1`,
  `mem_limit: 128m` (~70 Mo §4 / binaire Go ~50 Mo §6), reseaux `edge-net` +
  `backend-net`, **port 443 publie**, `/var/run/docker.sock` monte en
  **LECTURE SEULE (:ro)**.
- Rend la **config statique** (`traefik.yml`) : entrypoint `websecure` (:443),
  provider **Docker** (`exposedByDefault: false`), provider **file** (config
  dynamique), endpoint **ping** (healthcheck).
- Rend la **config dynamique TLS** (`dynamic/tls.yml`) : `defaultCertificate`
  (idiome Traefik 3.x `tls.stores.default.defaultCertificate`) referencant un
  **cert auto-signe**.
- Genere le **cert auto-signe** de dev : **mkcert** si present sur l'hote
  (CA de confiance, pas d'avertissement navigateur), sinon **openssl** en
  fallback. Idempotent via `creates:` sur le `.crt`.

## Ce que le role NE fait PAS (assume, pas un oubli)
- **Pas de ForwardAuth** : `identity-service` n'existe pas encore (§6).
- **Pas de regle de routage applicative** : aucun service a router a ce stade.
  Le routage viendra par **labels Docker** quand les services seront livres.
- **Pas de dashboard sur edge-net** : `traefik_dashboard_enabled: false` par
  defaut ; s'il est active, il reste **INTERNE** (`backend-net`), jamais public.

## Decision securite ACTEE (documenter, pas debattre)
Le provider Docker exige `/var/run/docker.sock` dans le conteneur. **Risque
quasi-root accepte** (pas de docker-socket-proxy, juge trop lourd pour un projet
solo 8 Go), **mitige par le montage en lecture seule (:ro)** — minimum vital,
pas une option. **Reevaluer si ce projet visait une vraie prod.**

## Secrets
**Aucun.** Les certs sont generes localement (mkcert/openssl) ; Traefik ne
consomme aucun secret Vault a ce stade.

## Test (idempotence + validite de config prouvees)
Scenario Molecule (driver docker, conteneurs freres — Linux natif, pas de DinD).
L'image officielle Traefik etant basee `scratch` (aucun shell/python), le
scenario tourne en **approche controleur** (`connection: local`, comme le verify
de `vault`) : il rend certs/configs/fragment dans `/tmp` (hote), puis **demarre
un VRAI conteneur `traefik:v3.7.1`** contre cette config. Traefik **fail-fast**
sur toute cle statique inconnue -> **demarrage sain (healthcheck `/ping`) =
preuve empirique de validite** (plus fort qu'une lecture de doc).

`verify` asserte : image epinglee, `edge-net` + `backend-net`, port 443 publie,
`mem_limit`, socket en `:ro` (**mode exact**), **aucune** regle de routage
applicative, TLS reference des certs **presents sur le disque**, et le vrai
Traefik devient **healthy**. Un 2e converge rapporte **changed=0**.

```bash
cd ansible/roles/traefik/molecule/default
molecule test
```