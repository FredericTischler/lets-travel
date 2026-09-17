#!/usr/bin/env bash
# Lance le scenario k6 contre la gateway Traefik et compte, apres coup, combien
# de requetes chaque replique a reellement servi (log applicatif RequestIdFilter,
# correle par le X-Request-Id `k6-<TAG>-<VU>-<ITER>` injecte par le script).
#
# Lecture seule vis-a-vis de la stack : aucun conteneur n'est demarre/arrete ici.
# Le scenario de failover (docker stop/start d'une replique) se pilote a la main,
# voir README.md.
#
# Usage :
#   ./load-tests/run-load-test.sh [TAG] [RATE] [DURATION]
# Exemple :
#   ./load-tests/run-load-test.sh baseline 30 30s
set -euo pipefail

TAG="${1:-baseline}"
RATE="${2:-30}"
DURATION="${3:-30s}"
SERVICE="${SERVICE:-identity-service}"
REPLICAS="${REPLICAS:-2}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.3.0}"
EDGE_NET="${EDGE_NET:-edge-net}"
TRAEFIK_CONTAINER="${TRAEFIK_CONTAINER:-travel-plan-traefik}"

cd "$(dirname "$0")/.."

# Le conteneur k6 joint edge-net (le seul reseau ou Traefik ecoute pour le
# monde exterieur) et resout identity.localhost vers l'IP de Traefik : on passe
# donc par le meme chemin TLS + routage que n'importe quel client externe.
TRAEFIK_IP="$(docker inspect "$TRAEFIK_CONTAINER" \
  --format "{{index .NetworkSettings.Networks \"$EDGE_NET\" \"IPAddress\"}}")"

echo "== k6 ($TAG) : ${RATE} req/s pendant ${DURATION} via Traefik ${TRAEFIK_IP}"
docker run --rm -i \
  --network "$EDGE_NET" \
  --add-host "identity.localhost:${TRAEFIK_IP}" \
  -e "TAG=${TAG}" -e "RATE=${RATE}" -e "DURATION=${DURATION}" \
  "$K6_IMAGE" run - < load-tests/health-load.js

echo
echo "== Repartition observee entre les repliques de ${SERVICE}"
for i in $(seq 1 "$REPLICAS"); do
  container="travel-plan-${SERVICE}-${i}"
  count="$(docker logs --since 10m "$container" 2>&1 | grep -c "k6-${TAG}-" || true)"
  echo "  ${container}: ${count} requetes"
done
