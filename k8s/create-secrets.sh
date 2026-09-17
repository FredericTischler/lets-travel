#!/usr/bin/env bash
#
# Cree le Secret Kubernetes `travel-plan-secrets` a partir de VARIABLES
# D'ENVIRONNEMENT. A jouer AVANT `kubectl apply -f k8s/`.
#
# POURQUOI UN SCRIPT ET PAS UN MANIFESTE `kind: Secret` DANS LE DEPOT
# ----------------------------------------------------------------------------
# Un objet Secret Kubernetes n'est PAS chiffre : `data:` est du base64, un
# encodage reversible par quiconque sait taper `base64 -d`. Committer un
# manifeste Secret rempli reviendrait exactement a committer les mots de passe
# en clair, avec en prime l'illusion du contraire. C'est incompatible avec la
# regle "zero secret en clair" du projet (docs §5, role Ansible app-secrets qui
# lit les secrets depuis Vault).
#
# Un manifeste Secret VIDE, avec des valeurs a remplir a la main, serait pire
# encore : il s'appliquerait sans erreur et les Pods demarreraient avec des
# chaines vides, produisant des echecs d'authentification tardifs et opaques.
#
# D'ou ce script : les valeurs viennent de l'environnement, ne touchent jamais
# le disque du depot, et leur ABSENCE fait echouer la commande immediatement.
#
# FAIL-FAST : `${VAR:?...}` est exactement l'idiome deja utilise par les
# fragments Docker Compose du projet (DB_PASSWORD: "${IDENTITY_DB_PASSWORD:?set
# via env/Vault}"). Meme discipline des deux cotes : jamais de valeur par
# defaut, qui masquerait un secret non provisionne jusqu'au runtime.
#
# SOURCE DES VALEURS
# ----------------------------------------------------------------------------
# Les noms de variables ci-dessous sont IDENTIQUES a ceux du fichier
# /opt/travel-plan/.env rendu par le role Ansible `app-secrets` depuis Vault
# (ansible/roles/app-secrets/templates/dotenv.j2). Sur une machine ou le
# deploiement Ansible a deja tourne, il suffit donc de sourcer ce fichier :
#
#   set -a && . /opt/travel-plan/.env && set +a
#   export VAULT_DEV_ROOT_TOKEN_ID=...      # non present dans le .env
#   ./k8s/create-secrets.sh
#
# Sinon, exporter les variables a la main (valeurs de developpement, sandbox).
#
# Usage :
#   ./k8s/create-secrets.sh              # cree ou met a jour le Secret
#
set -euo pipefail

NAMESPACE="${NAMESPACE:-travel-plan}"
SECRET_NAME="${SECRET_NAME:-travel-plan-secrets}"

# Le namespace doit exister avant le Secret. `--dry-run=client -o yaml | apply`
# est l'idiome idempotent standard de kubectl : `kubectl create` seul echouerait
# avec AlreadyExists au second passage, ce qui rendrait ce script rejouable une
# seule fois. Ici, un second passage ne produit aucune erreur et reconcilie les
# valeurs — meme exigence d'idempotence que les roles Ansible du projet.
kubectl create namespace "$NAMESPACE" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic "$SECRET_NAME" \
  --namespace "$NAMESPACE" \
  --from-literal=POSTGRES_SUPERUSER_PASSWORD="${POSTGRES_SUPERUSER_PASSWORD:?a exporter (cf. /opt/travel-plan/.env ou Vault infra/postgres-superuser)}" \
  --from-literal=IDENTITY_DB_PASSWORD="${IDENTITY_DB_PASSWORD:?a exporter (Vault identity/db)}" \
  --from-literal=PAYMENT_DB_PASSWORD="${PAYMENT_DB_PASSWORD:?a exporter (Vault payment/db)}" \
  --from-literal=NEO4J_PASSWORD="${NEO4J_PASSWORD:?a exporter (Vault travel/db)}" \
  --from-literal=JWT_SIGNING_KEY="${JWT_SIGNING_KEY:?a exporter (Vault shared/jwt, >= 32 octets pour HS256)}" \
  --from-literal=STRIPE_API_KEY="${STRIPE_API_KEY:?a exporter (Vault payment/stripe)}" \
  --from-literal=STRIPE_SECRET_KEY="${STRIPE_SECRET_KEY:?a exporter (Vault payment/stripe)}" \
  --from-literal=PAYPAL_CLIENT_ID="${PAYPAL_CLIENT_ID:?a exporter (Vault payment/paypal)}" \
  --from-literal=PAYPAL_CLIENT_SECRET="${PAYPAL_CLIENT_SECRET:?a exporter (Vault payment/paypal)}" \
  --from-literal=VAULT_DEV_ROOT_TOKEN_ID="${VAULT_DEV_ROOT_TOKEN_ID:?a exporter (root token du Vault en dev mode)}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Secret ${NAMESPACE}/${SECRET_NAME} cree ou mis a jour."
echo
echo "ATTENTION : modifier ce Secret ne met PAS a jour les Pods deja demarres."
echo "Les valeurs injectees par secretKeyRef sont lues UNE FOIS au demarrage du"
echo "conteneur (contrairement a un Secret monte en volume, rafraichi par le"
echo "kubelet). Pour propager un changement :"
echo "  kubectl -n ${NAMESPACE} rollout restart deployment/identity-service deployment/payment-service deployment/travel-service"
