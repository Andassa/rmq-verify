#!/usr/bin/env bash
# Scripted terminal demo, reproducible frame by frame. Record it with:
#   asciinema rec docs/demo.cast --overwrite -c docs/demo.sh
#   agg docs/demo.cast docs/demo.gif          # optional GIF, https://github.com/asciinema/agg
# Requires a built jar (make build) and Docker. Uses a fixed seed, so the fault plan is identical
# on every recording; the broker's behaviour is not (see docs/METHODOLOGY.md, section 6).
set -euo pipefail
cd "$(dirname "$0")/.."

jar=rmq-verify-cli/target/rmq-verify.jar
pause() { sleep "${DEMO_PAUSE:-1.5}"; }
say() { printf '\n\033[1;36m# %s\033[0m\n' "$*"; pause; }
run() {
  printf '\033[1;32m$\033[0m '
  for ((i = 0; i < ${#1}; i++)); do printf '%s' "${1:i:1}"; sleep 0.02; done
  printf '\n'
  eval "$1"
  pause
}

say "A three-node RabbitMQ 4.4 cluster behind Toxiproxy, with generated credentials"
run "make up 2>&1 | tail -n 2"

say "The seed fixes the fault plan. With seed 42 the only fault is a partition around rabbit2"
run "java -jar $jar plan --seed 42 --duration 60 --nemesis kill-node,partition-halves | head -n 22"

say "Run the workload under those faults on a quorum queue, then check the history"
run "java -jar $jar run --queue-type QUORUM --consumers 2 --nemesis kill-node,partition-halves --duration 60 --seed 42 --scenario demo --out runs/demo-cast 2>/dev/null || true"

say "Every verdict can be recomputed offline from the history alone"
run "java -jar $jar check runs/demo-cast/history.jsonl"

say "The control scenario loses messages on purpose (x-max-length + drop-head): the checkers must catch it"
run "java -jar $jar run --queue-type CLASSIC --max-length 50 --consumers 0 --duration 20 --scenario control --out runs/demo-control 2>/dev/null | tail -n 8 || true"

say "Reports: runs/demo-cast/report.html (self-contained) and report.md"
run "ls runs/demo-cast"
