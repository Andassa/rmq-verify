#!/usr/bin/env bash
# Shared helpers for the deploy scripts. Sourced, never executed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_DIR="$ROOT_DIR/deploy"
ENV_FILE="$ROOT_DIR/.env"
RABBITMQ_VERSION="${RABBITMQ:-4.4}"

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
  docker compose --project-directory "$DEPLOY_DIR" -f "$DEPLOY_DIR/docker-compose.yml" --env-file "$ENV_FILE" "$@"
}

# Runs rabbitmqctl on rabbit1 as the broker user.
rabbitmqctl() {
  docker exec rmqv-rabbit1 rabbitmqctl "$@"
}
