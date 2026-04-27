#!/usr/bin/env bash
# Generuje self-signed TLS cert pro nginx a RSA keypair pro JWT (dev only).
# Před prvním `docker compose up` spusť tento skript.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CERT_DIR="$ROOT_DIR/nginx/certs"
SECRETS_DIR="$ROOT_DIR/secrets"

mkdir -p "$CERT_DIR" "$SECRETS_DIR"

# --------- TLS cert pro nginx (self-signed, SAN s IP 46.23.61.86) ---------
if [[ ! -f "$CERT_DIR/server.crt" || ! -f "$CERT_DIR/server.key" ]]; then
    echo ">> Generating self-signed TLS cert (4096-bit RSA, 365 dní)"
    openssl req -x509 -newkey rsa:4096 -sha256 -nodes \
        -keyout "$CERT_DIR/server.key" \
        -out    "$CERT_DIR/server.crt" \
        -days   365 \
        -subj   "/C=CZ/O=TrashTalk/CN=46.23.61.86" \
        -addext "subjectAltName=IP:46.23.61.86,IP:127.0.0.1,DNS:localhost"
    chmod 600 "$CERT_DIR/server.key"
else
    echo "== TLS cert již existuje v $CERT_DIR — preskakuji"
fi

# --------- RSA keypair pro JWT (RS256) ---------
if [[ ! -f "$SECRETS_DIR/jwt_private.pem" ]]; then
    echo ">> Generating JWT RSA keypair (2048-bit)"
    openssl genrsa -out "$SECRETS_DIR/jwt_private.pem" 2048
    openssl rsa -in "$SECRETS_DIR/jwt_private.pem" -pubout -out "$SECRETS_DIR/jwt_public.pem"
    chmod 600 "$SECRETS_DIR/jwt_private.pem"
else
    echo "== JWT keypair již existuje v $SECRETS_DIR — preskakuji"
fi

echo ""
echo "== Hotovo. Soubory:"
echo "   $CERT_DIR/server.crt"
echo "   $CERT_DIR/server.key"
echo "   $SECRETS_DIR/jwt_private.pem"
echo "   $SECRETS_DIR/jwt_public.pem"
echo ""
echo "Spusť backend: docker compose up -d"
