#!/usr/bin/env bash
# Shared helpers for the deploy scripts. Sourced, never executed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_DIR="$ROOT_DIR/deploy"
ENV_FILE="$ROOT_DIR/.env"
RABBITMQ_VERSION="${RABBITMQ:-4.4}"
RMQV_JAR="${RMQV_JAR:-$ROOT_DIR/rmq-verify-cli/target/rmq-verify.jar}"

log() { printf '[rmq-verify] %s\n' "$*" >&2; }
die() { log "error: $*"; exit 1; }

# Resolves RABBITMQ (3.13 or 4.4) to the pinned image of deploy/images.env.
resolve_images() {
  # shellcheck disable=SC1091
  source "$DEPLOY_DIR/images.env"
  local key="RABBITMQ_${RABBITMQ_VERSION//./_}"
  RMQV_RABBITMQ_BASE_IMAGE="${!key:-}"
  [ -n "$RMQV_RABBITMQ_BASE_IMAGE" ] || die "unsupported RABBITMQ=$RABBITMQ_VERSION (see deploy/images.env)"
  local tag="${RMQV_RABBITMQ_BASE_IMAGE#rabbitmq:}"
  RMQV_RABBITMQ_TAG="${tag%%@*}"
  RMQV_TOXIPROXY_IMAGE="$TOXIPROXY"
  export RMQV_RABBITMQ_BASE_IMAGE RMQV_RABBITMQ_TAG RMQV_TOXIPROXY_IMAGE
}

compose() {
  local files=(-f "$DEPLOY_DIR/docker-compose.yml")
  if [ "${TLS:-0}" = "1" ]; then
    files+=(-f "$DEPLOY_DIR/docker-compose.tls.yml")
  fi
  docker compose --project-directory "$DEPLOY_DIR" "${files[@]}" --env-file "$ENV_FILE" "$@"
}

# TLS mode: test certificates plus a conf.d without plain listeners (see docker-compose.tls.yml).
prepare_tls() {
  "$DEPLOY_DIR/scripts/gen-test-certs.sh"
  local conf="$DEPLOY_DIR/tls/generated/conf.d"
  mkdir -p "$conf"
  sed -e '/^listeners\.tcp/d' -e '/^stream\.listeners\.tcp/d' \
    "$DEPLOY_DIR/rabbitmq/conf.d/10-rmq-verify.conf" > "$conf/10-rmq-verify.conf"
  cp "$DEPLOY_DIR/rabbitmq/tls/20-tls.conf" "$conf/20-tls.conf"
}

# Runs rabbitmqctl on rabbit1 as the broker user.
rabbitmqctl() {
  docker exec rmqv-rabbit1 rabbitmqctl "$@"
}
