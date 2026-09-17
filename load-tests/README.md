# Tests de charge — load balancing et failover derriere Traefik

Le sujet demande de simuler une charge sur les microservices et de documenter
leur comportement : repartition entre repliques et tenue en cas de panne d'une
replique. Ce dossier contient le scenario rejouable et les mesures obtenues.

## Contenu

| Fichier | Role |
| --- | --- |
| `health-load.js` | Scenario k6 (debit constant, TLS, en-tete `X-Request-Id` traceable) |
| `run-load-test.sh` | Lance k6 dans un conteneur et compte les requetes servies par chaque replique |

Aucun outil a installer sur l'hote : k6 tourne via l'image `grafana/k6:1.3.0`.
Le conteneur k6 est attache a `edge-net` et resout `identity.localhost` vers
l'IP de Traefik, il emprunte donc exactement le chemin d'un client externe
(TLS 443 -> routeur Traefik -> pool des repliques -> Spring Boot -> Postgres).

```bash
./load-tests/run-load-test.sh baseline 30 30s
```

La repartition n'est pas lue dans un access log Traefik (il n'est pas active
dans la config statique du role `traefik`, et l'activer aurait impose un
redemarrage de la gateway) mais **cote applicatif** : chaque service loggue
`METHODE URI -> STATUT (Nms)` avec son `requestId` via `RequestIdFilter`. k6
envoie un `X-Request-Id` de la forme `k6-<phase>-<vu>-<iteration>`, il suffit
donc de compter ces lignes dans les logs de chaque conteneur replique.

## Conditions de mesure

- Cible : `GET https://identity.localhost/actuator/health` (2 repliques
  `travel-plan-identity-service-1` et `-2`, `deploy.replicas: 2`).
- Debit : 30 req/s en arrivees constantes (`constant-arrival-rate`, le debit ne
  s'auto-regule donc pas si le backend ralentit — indispensable pour mesurer un
  failover honnetement).
- Machine de dev partagee avec une vingtaine d'autres conteneurs : la charge est
  volontairement modeste, on illustre un comportement, on ne cherche pas le
  point de rupture.

## Resultat 1 — repartition nominale (round-robin)

30 req/s pendant 30 s, les deux repliques up : **901 requetes, 0 echec**.

| Metrique | Valeur |
| --- | --- |
| Requetes | 901 (30,02/s) |
| Echecs | 0,00 % |
| Latence mediane | 3,90 ms |
| Latence p95 | 8,05 ms |
| Latence max | 23,15 ms |

Repartition constatee dans les logs applicatifs :

```
travel-plan-identity-service-1 : 450 requetes
travel-plan-identity-service-2 : 451 requetes
```

Soit 49,9 % / 50,1 % : le round-robin de Traefik (strategie par defaut du
load balancer du provider Docker) est bien effectif entre les deux repliques
declarees sous le meme `traefik.http.services.identity`.

## Resultat 2 — failover (arret d'une replique sous charge)

Meme debit, 60 s de charge. Chronologie (UTC) :

| Instant | Evenement |
| --- | --- |
| 09:38:07 | debut effectif du trafic k6 |
| 09:38:14 | `docker stop travel-plan-identity-service-2` |
| 09:38:15.257 | derniere requete servie par la replique 2 |
| 09:38:41 | `docker start travel-plan-identity-service-2` |
| 09:38:50.427 | `Started IdentityServiceApplication in 7.887 seconds` |
| 09:38:52.326 | premiere requete de nouveau servie par la replique 2 |

Resultats k6 sur les 1 800 requetes du run :

| Metrique | Valeur |
| --- | --- |
| Requetes | 1 800 (29,99/s) |
| Reponses 2xx | 1 779 |
| Reponses non-2xx | 15 |
| Echecs transport (connexion coupee / timeout 5 s) | 6 |
| Taux d'echec global | **1,16 %** |
| p95 des reponses servies | 8,46 ms |
| p95 global (echecs inclus) | 9,37 ms |

Repartition sur le run complet : replique 1 = 1 434 requetes, replique 2 = 345
(129 avant l'arret, 216 apres le redemarrage). Pendant les ~37 s ou la replique
2 etait absente, **la replique 1 a absorbe 100 % du trafic a 30 req/s sans
degradation de latence** : la mediane des reponses servies reste a 1,82 ms.

Le cout de la bascule est donc borne a la fenetre de l'arret lui-meme : les 21
requetes perdues correspondent a ~0,7 s de trafic, soit les requetes deja en vol
ou emises vers la replique mourante avant que Traefik ne la retire du pool.
Aucune erreur n'a ete observee ensuite, ni pendant l'absence de la replique, ni
au moment de son retour.

### Verification ciblee des codes d'erreur

Pour qualifier ces erreurs, une sonde sequentielle (300 requetes espacees de
200 ms) a ete rejouee sur un cycle stop/start complet :

```
299 x HTTP 200
  1 x HTTP 502   <- horodatee a la seconde exacte du docker stop
```

L'unique erreur est un **502 Bad Gateway** emis par Traefik pour une requete
routee vers le conteneur en train de s'arreter. Aucune erreur au redemarrage :
la premiere requete n'atteint la replique 2 que ~2 s apres le `Started ...` de
Spring, donc apres que le healthcheck Compose (`interval: 10s`) l'ait fait
passer `healthy` — comportement coherent avec un provider Docker qui ne
reintegre pas au pool un conteneur dont l'etat de sante n'est pas encore bon.
Le healthcheck applicatif joue donc son role de garde-fou au retour : il n'y a
pas de fenetre "conteneur demarre mais application pas prete" visible du client.

## Limites et pistes

- Il n'y a **pas de retry** configure cote Traefik : la poignee de requetes en
  vol au moment de l'arret est perdue telle quelle. Un middleware `retry`
  (idempotent uniquement, donc GET) ramenerait ce taux a ~0 au prix d'une
  latence de queue plus longue. A arbitrer, non fait ici.
- L'arret teste est un `docker stop` brutal du point de vue du client. Un arret
  propre en production passerait par un retrait prealable du pool
  (poids a 0 / drain) avant l'arret du conteneur.
- La cible est `/actuator/health` : elle traverse la chaine complete y compris
  une validation de connexion Postgres, mais reste bien plus legere qu'un
  endpoint metier. Les latences ci-dessus mesurent la gateway et le routage,
  pas le cout d'un cas d'usage reel.
- Etat de la stack a la fin de la campagne : les deux repliques
  `travel-plan-identity-service-1` et `-2` sont `Up (healthy)`.
