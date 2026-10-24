#!/usr/bin/env bash
# Standard scenario matrix, sourced by chaos.sh and bench.sh. One line per scenario:
#   name|rmq-verify run options
# Scenarios with --consumers 0 are store-and-forward: everything is read by the final drain, after
# the faults, which is the strictest durability test. The others also exercise ordering and
# duplicates while faults happen.
FAULTS="--fault-min 8 --fault-max 15 --gap-min 5 --gap-max 10"
SCENARIOS=(
  "classic-maxlength-control|--queue-type CLASSIC --max-length 100 --consumers 0"
  "classic-kill|--queue-type CLASSIC --consumers 0 --nemesis kill-node $FAULTS"
  "quorum-kill-pause|--queue-type QUORUM --consumers 0 --nemesis kill-node,pause-node $FAULTS"
  "quorum-partitions|--queue-type QUORUM --consumers 2 --nemesis partition-halves,partition-isolate,partition-bridge $FAULTS"
  "quorum-network|--queue-type QUORUM --consumers 2 --nemesis network-latency,packet-loss,client-toxic $FAULTS"
  "stream-kill-partition|--queue-type STREAM --consumers 1 --nemesis kill-node,partition-halves $FAULTS"
)
