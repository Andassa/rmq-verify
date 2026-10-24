#!/usr/bin/env bash
# Runs every scenario of scenarios.sh REPS times against the cluster that is currently up.
# Usage: matrix.sh <output-dir> <reps> <duration-seconds>
# Seeds are fixed per scenario and repetition, so both RabbitMQ versions receive the same fault
# plans and workloads.
source "$(dirname "$0")/lib.sh"
source "$(dirname "$0")/scenarios.sh"

out="$1"
reps="$2"
duration="$3"
jar="$RMQV_JAR"
[ -f "$jar" ] || die "build the CLI first (make build)"
version="$(rabbitmqctl version 2>/dev/null | tail -n1)"
[ -n "$version" ] || die "no running cluster (make up)"

index=0
for entry in "${SCENARIOS[@]}"; do
  name="${entry%%|*}"
  options="${entry#*|}"
  index=$((index + 1))
  for rep in $(seq 1 "$reps"); do
    dir="$out/$version/$name-$rep"
    if [ -f "$dir/report.json" ]; then
      log "skip $name #$rep on $version (already done)"
      continue
    fi
    mkdir -p "$(dirname "$dir")"
    seed=$((20261024 + 1000 * index + rep))
    log "scenario $name #$rep on RabbitMQ $version (seed $seed)"
    # shellcheck disable=SC2086 # options are a word list by design
    java -jar "$jar" run $options --duration "$duration" --publishers 4 --rate 30 --seed "$seed" \
      --scenario "$name" --repetition "$rep" --out "$dir" --env-file "$ENV_FILE" \
      > "$dir.log" 2>&1 || true
    if [ -f "$dir/history.jsonl" ]; then
      gzip -f -9 "$dir/history.jsonl"
    fi
    mv -f "$dir.log" "$dir/run.log" 2>/dev/null || true
    grep -E '^(run |artefacts|[A-Z][A-Za-z]+ +(VALID|INVALID|UNKNOWN))' "$dir/run.log" | sed 's/^/    /' >&2 || true
  done
done
