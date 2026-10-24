#!/usr/bin/env bash
# Stops the cluster and removes its containers, network and volumes. Idempotent.
source "$(dirname "$0")/lib.sh"

if [ ! -f "$ENV_FILE" ]; then
  log "no .env, nothing was started"
  exit 0
fi
resolve_images
compose down --volumes --remove-orphans --timeout 20
log "cluster removed"
