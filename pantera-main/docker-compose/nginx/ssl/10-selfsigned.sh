#!/bin/sh
# Generates a throwaway self-signed certificate for the DEV nginx TLS
# listener when none is present. Runs from /docker-entrypoint.d at
# container start (nginx official image). Nothing is committed to git:
# nginx.key/nginx.crt are ignored — every checkout gets its own key
# (2.2.9: the previously committed key was a real private key in the
# repository history).
set -e
DIR=/etc/nginx/ssl
if [ ! -f "$DIR/nginx.key" ] || [ ! -f "$DIR/nginx.crt" ]; then
  echo "[pantera] generating self-signed dev certificate in $DIR"
  openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
    -keyout "$DIR/nginx.key" -out "$DIR/nginx.crt" \
    -config "$DIR/openssl.cnf" >/dev/null 2>&1
  chmod 600 "$DIR/nginx.key"
fi
