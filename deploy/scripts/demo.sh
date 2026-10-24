#!/usr/bin/env bash
# Starts the cluster, runs a 60-second quorum-queue scenario under kill and partition faults, and
# opens the HTML report.
source "$(dirname "$0")/lib.sh"

"$DEPLOY_DIR/scripts/up.sh"
dir="$ROOT_DIR/runs/demo-$(date -u +%Y%m%d-%H%M%S)"
java -jar "$RMQV_JAR" run --queue-type QUORUM --consumers 2 \
  --nemesis kill-node,partition-halves --duration 60 --seed 42 --scenario demo --out "$dir" \
  --env-file "$ENV_FILE" || true
report="$dir/report.html"
[ -f "$report" ] || die "no report produced, see the output above"
if command -v open >/dev/null 2>&1; then
  open "$report"
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$report"
fi
log "report: $report"
