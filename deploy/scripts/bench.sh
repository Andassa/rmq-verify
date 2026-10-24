#!/usr/bin/env bash
# Full benchmark: every scenario, REPS repetitions, on every pinned RabbitMQ version, then
# regenerates RESULTS.md from the archived report.json files. Resumable: finished runs are kept.
source "$(dirname "$0")/lib.sh"

versions="${VERSIONS:-3.13 4.4}"
reps="${REPS:-2}"
duration="${DURATION:-60}"
out="$ROOT_DIR/results"
mkdir -p "$out"

for v in $versions; do
  RABBITMQ="$v" "$DEPLOY_DIR/scripts/down.sh"
  RABBITMQ="$v" "$DEPLOY_DIR/scripts/up.sh"
  RABBITMQ="$v" "$DEPLOY_DIR/scripts/matrix.sh" "$out" "$reps" "$duration"
done
"$DEPLOY_DIR/scripts/down.sh"

host="$(uname -sm), $(getconf _NPROCESSORS_ONLN 2>/dev/null || echo '?') CPUs, Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
java -jar "$RMQV_JAR" report --matrix "$out" --results-md "$ROOT_DIR/RESULTS.md" --readme "$ROOT_DIR/README.md" \
  --commit "$(git -C "$ROOT_DIR" rev-parse --short HEAD)" --images "$DEPLOY_DIR/images.env" --host "$host"
log "RESULTS.md regenerated from $(find "$out" -name report.json | wc -l | tr -d ' ') runs"
