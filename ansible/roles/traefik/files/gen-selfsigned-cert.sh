#!/usr/bin/env bash
# gen-selfsigned-cert.sh
#
# Genere un certificat TLS auto-signe pour la terminaison TLS au bord (docs §5).
# Preference mkcert (CA locale de confiance, pas d'avertissement navigateur) si
# present sur l'hote ; sinon fallback openssl (cert auto-signe brut).
#
# IDEMPOTENCE : ce script n'est appele par Ansible qu'avec `creates:` sur le
# fichier .crt (voir tasks/main.yml). Il ne s'execute donc PAS si le cert existe
# deja. En defense en profondeur, il re-verifie et sort 0 sans rien regenerer si
# les deux fichiers sont deja presents.
set -euo pipefail

CERT_FILE="${1:?usage: gen-selfsigned-cert.sh <cert_file> <key_file> <common_name> <days> <san_csv>}"
KEY_FILE="${2:?key_file requis}"
COMMON_NAME="${3:?common_name requis}"
DAYS="${4:?days requis}"
SAN_CSV="${5:?san_csv (dns1,dns2,...) requis}"

if [[ -s "${CERT_FILE}" && -s "${KEY_FILE}" ]]; then
  echo "cert deja present (${CERT_FILE}) : rien a faire."
  exit 0
fi

# Construit l'extension SAN : DNS:localhost,DNS:travel-plan.localhost -> ...
SAN_EXT=""
IFS=',' read -ra SANS <<<"${SAN_CSV}"
for dns in "${SANS[@]}"; do
  [[ -n "${dns}" ]] || continue
  SAN_EXT+="${SAN_EXT:+,}DNS:${dns}"
done
: "${SAN_EXT:=DNS:${COMMON_NAME}}"

if command -v mkcert >/dev/null 2>&1; then
  echo "mkcert detecte : generation d'un cert signe par la CA locale mkcert."
  # -install est idempotent (n'installe la CA que si absente).
  mkcert -install >/dev/null 2>&1 || true
  mkcert -cert-file "${CERT_FILE}" -key-file "${KEY_FILE}" "${SANS[@]}"
else
  echo "mkcert absent : fallback openssl (cert auto-signe brut)."
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "${KEY_FILE}" \
    -out "${CERT_FILE}" \
    -days "${DAYS}" \
    -subj "/CN=${COMMON_NAME}" \
    -addext "subjectAltName=${SAN_EXT}"
fi

echo "cert genere : ${CERT_FILE}"