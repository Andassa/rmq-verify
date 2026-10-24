#!/usr/bin/env bash
# One pass of the scenario matrix on the cluster that is up (make up RABBITMQ=...), written to
# runs/chaos-<timestamp>/ with its own matrix page. RESULTS.md is not touched (see bench.sh).
source "$(dirname "$0")/lib.sh"

out="$ROOT_DIR/runs/chaos-$(date -u +%Y%m%d-%H%M%S)"
mkdir -p "$out"
"$DEPLOY_DIR/scripts/matrix.sh" "$out" "${REPS:-1}" "${DURATION:-60}"
java -jar "$RMQV_JAR" report --matrix "$out" --results-md "$out/MATRIX.md" \
  --commit "$(git -C "$ROOT_DIR" rev-parse --short HEAD)" --images "$DEPLOY_DIR/images.env" --host "$(uname -sm)"
log "chaos matrix: $out/MATRIX.md"
