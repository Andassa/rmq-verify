#!/usr/bin/env bash
# Generates a throwaway CA and one server certificate for the local test cluster, valid 30 days.
# FOR TESTS ONLY: the keys are unencrypted and readable by the broker container user.
source "$(dirname "$0")/lib.sh"

dir="$DEPLOY_DIR/tls/generated/certs"
if [ -f "$dir/server.pem" ] && openssl x509 -checkend 86400 -noout -in "$dir/server.pem" >/dev/null 2>&1; then
  log "test certificates in $dir are still valid, keeping them"
  exit 0
fi
mkdir -p "$dir"
cd "$dir"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 30 \
  -subj "/CN=rmq-verify test CA" -keyout ca.key -out ca.pem 2>/dev/null
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
  -subj "/CN=rmq-verify test broker" -keyout server.key -out server.csr 2>/dev/null
cat > server.ext <<EOF
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:rabbit1, DNS:rabbit2, DNS:rabbit3, DNS:localhost, IP:127.0.0.1
EOF
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 30 \
  -extfile server.ext -out server.pem 2>/dev/null
rm -f server.csr server.ext ca.srl
# The broker runs as uid 999 inside the container and reads the key through a bind mount.
chmod 644 ca.pem server.pem server.key
chmod 600 ca.key
log "test CA and broker certificate written to $dir (tests only)"
