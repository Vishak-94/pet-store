#!/usr/bin/env bash
# Generates the RSA keypair auth-service uses to sign RS256 tokens.
# The PRIVATE key stays local (gitignored); the PUBLIC key is committed so the
# verifier services (via auth-client) can validate tokens.
#
# Run this once before the first build:  ./generate-keys.sh
set -euo pipefail
cd "$(dirname "$0")"

KEYS=auth-service/keys
mkdir -p "$KEYS"

echo "==> generating RSA-2048 private key (PKCS#8)"
openssl genrsa -out "$KEYS/private_key.pem" 2048 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$KEYS/private_key.pem" -out "$KEYS/private_key_pkcs8.pem"

echo "==> extracting public key"
openssl rsa -in "$KEYS/private_key.pem" -pubout -out "$KEYS/public_key.pem" 2>/dev/null

echo "==> placing keys where the apps load them"
# auth-service (issuer) reads the private key from its resources
cp "$KEYS/private_key_pkcs8.pem" auth-service/app/resources/auth-private-key.pem
# auth-client (verifiers) bundles the public key
cp "$KEYS/public_key.pem"       auth-service/client/resources/petstore-auth-public.pem

echo "DONE. Private key is local-only (gitignored); public key is committed."
