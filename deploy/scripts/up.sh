#!/usr/bin/env bash
# Starts the three-node cluster for RABBITMQ (3.13 or 4.4), waits until it is formed and creates
# the least-privileged application user. Idempotent.
source "$(dirname "$0")/lib.sh"

"$DEPLOY_DIR/scripts/init-env.sh"
resolve_images
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

if [ "${TLS:-0}" = "1" ]; then
  prepare_tls
fi
log "starting RabbitMQ $RMQV_RABBITMQ_TAG cluster (TLS=${TLS:-0})"
compose up -d --build --wait --wait-timeout 300

log "waiting for the three nodes to join"
for _ in $(seq 1 60); do
  running="$(rabbitmqctl cluster_status --formatter json 2>/dev/null \
    | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["running_nodes"]))' 2>/dev/null || echo 0)"
  [ "$running" = "3" ] && break
  sleep 2
done
[ "$running" = "3" ] || die "cluster did not form (running nodes: $running)"

log "importing definitions and configuring $RMQV_APP_USER on vhost $RMQV_VHOST"
rabbitmqctl import_definitions /etc/rabbitmq/definitions.json >/dev/null
if rabbitmqctl list_users --formatter json | grep -q "\"$RMQV_APP_USER\""; then
  printf '%s' "$RMQV_APP_PASSWORD" | docker exec -i rmqv-rabbit1 rabbitmqctl change_password "$RMQV_APP_USER" >/dev/null
else
  printf '%s' "$RMQV_APP_PASSWORD" | docker exec -i rmqv-rabbit1 rabbitmqctl add_user "$RMQV_APP_USER" >/dev/null
fi
# The application may only touch queues named rmqv.* and publish through the default exchange.
rabbitmqctl set_permissions -p "$RMQV_VHOST" "$RMQV_APP_USER" '^rmqv\..*' '^(rmqv\..*|amq\.default)$' '^rmqv\..*' >/dev/null
rabbitmqctl set_permissions -p "$RMQV_VHOST" "$RMQV_ADMIN_USER" '.*' '.*' '.*' >/dev/null

log "cluster ready: RabbitMQ $(rabbitmqctl version 2>/dev/null | tail -n1), management on http://127.0.0.1:15672"
if [ "${TLS:-0}" = "1" ]; then
  log "TLS only: pass --tls-ca $DEPLOY_DIR/tls/generated/certs/ca.pem to rmq-verify"
fi
