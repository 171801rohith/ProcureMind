#!/usr/bin/env bash
#
# Generates the PKCS12 keystore that auth-service uses to sign OIDC tokens.
# The resulting .p12 is a secret: it is git-ignored and must never be committed.
#
# Usage:
#   AUTH_JWK_KEYSTORE_PASSWORD=changeit ./scripts/gen-auth-keystore.sh
#
# Then point auth-service at it, e.g.:
#   export AUTH_JWK_KEYSTORE_PATH="$(pwd)/auth-service/keys/auth-signing.p12"
#   export AUTH_JWK_KEYSTORE_PASSWORD=changeit
#   export AUTH_JWK_KEY_ALIAS=auth-signing
#   export AUTH_JWK_KEY_ID=auth-signing
#
# Without a keystore, auth-service falls back to a NON-PERSISTENT in-memory key (dev only).

set -euo pipefail

OUT_DIR="${AUTH_JWK_KEYSTORE_DIR:-auth-service/keys}"
OUT_FILE="${OUT_DIR}/auth-signing.p12"
ALIAS="${AUTH_JWK_KEY_ALIAS:-auth-signing}"
STOREPASS="${AUTH_JWK_KEYSTORE_PASSWORD:-changeit}"
VALIDITY_DAYS="${AUTH_JWK_KEY_VALIDITY_DAYS:-3650}"

mkdir -p "${OUT_DIR}"

if [[ -f "${OUT_FILE}" ]]; then
  echo "Keystore already exists: ${OUT_FILE} (delete it to regenerate)"
  exit 0
fi

keytool -genkeypair \
  -alias "${ALIAS}" \
  -keyalg RSA \
  -keysize 2048 \
  -sigalg SHA256withRSA \
  -validity "${VALIDITY_DAYS}" \
  -dname "CN=ProcureMind Auth Service,OU=ProcureMind,O=ProcureMind,C=US" \
  -storetype PKCS12 \
  -keystore "${OUT_FILE}" \
  -storepass "${STOREPASS}" \
  -keypass "${STOREPASS}"

echo "Created ${OUT_FILE} (alias='${ALIAS}')"
