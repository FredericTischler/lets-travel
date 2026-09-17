// Scenario de charge k6 - Travel Plan
//
// But : illustrer le comportement de la gateway Traefik devant les 2 repliques
// d'un microservice Spring Boot (round-robin) et son comportement en failover
// quand une replique disparait.
//
// Cible par defaut : GET https://identity.localhost/actuator/health
//   - endpoint non protege (pas de JWT a fabriquer, donc pas de secret dans ce
//     depot), traverse toute la chaine : TLS -> Traefik -> replique -> Postgres
//     (l'indicateur `db` de l'actuator fait un isValid() sur la connexion).
//
// Execution (image k6 officielle, rien a installer sur l'hote) :
//   TRAEFIK_IP=$(docker inspect travel-plan-traefik \
//     --format '{{.NetworkSettings.Networks.edge-net.IPAddress}}')
//   docker run --rm -i --network edge-net \
//     --add-host identity.localhost:"$TRAEFIK_IP" \
//     grafana/k6:1.3.0 run - < load-tests/health-load.js
//
// Parametres (variables d'env k6, surchargeables via -e CLE=valeur) :
//   TARGET_URL  URL testee
//   RATE        requetes/seconde (arrivees constantes, independant des latences)
//   DURATION    duree de la phase de charge
//   TAG         libelle libre reporte dans la sortie (ex: baseline / failover)
//
// Charge volontairement modeste : la machine de dev heberge deja une vingtaine
// de conteneurs. L'objectif est d'observer une repartition et un failover, pas
// de trouver le point de rupture.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const TARGET_URL = __ENV.TARGET_URL || 'https://identity.localhost/actuator/health';
const RATE = Number(__ENV.RATE || 30);
const DURATION = __ENV.DURATION || '30s';
const TAG = __ENV.TAG || 'baseline';

// Compteurs explicites : k6 compte deja les echecs, mais on veut la ventilation
// par code HTTP pour distinguer un 502/503 Traefik (backend absent) d'une erreur
// de transport (connexion coupee en plein vol lors du `docker stop`).
export const httpOk = new Counter('tp_http_2xx');
export const httpBad = new Counter('tp_http_non_2xx');
export const httpErr = new Counter('tp_transport_errors');

export const options = {
  // Certificat auto-signe local : on ne teste pas la PKI ici.
  insecureSkipTLSVerify: true,
  // constant-arrival-rate : le debit reste fixe meme si le backend ralentit,
  // ce qui est exactement ce qu'on veut pour mesurer un failover (avec des VUs
  // fixes, la charge s'auto-regulerait et masquerait l'incident).
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(10, RATE),
      maxVUs: Math.max(30, RATE * 2),
      tags: { phase: TAG },
    },
  },
  // Seuils indicatifs, non bloquants pour le run (le run failover les depasse
  // par construction : c'est la mesure, pas un echec de test).
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: false }],
    http_req_duration: [{ threshold: 'p(95)<500', abortOnFail: false }],
  },
};

export default function () {
  const res = http.get(TARGET_URL, {
    headers: { 'X-Request-Id': `k6-${TAG}-${__VU}-${__ITER}` },
    timeout: '5s',
    tags: { phase: TAG },
  });

  if (res.error_code >= 1000 && res.status === 0) {
    httpErr.add(1);
  } else if (res.status >= 200 && res.status < 300) {
    httpOk.add(1);
  } else {
    httpBad.add(1);
  }

  check(res, {
    'status 200': (r) => r.status === 200,
    'corps UP': (r) => typeof r.body === 'string' && r.body.includes('"status":"UP"'),
  });
}
