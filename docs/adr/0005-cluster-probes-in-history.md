# ADR-0005: Cluster probes are recorded in the history (`PROBE`)

- Status: accepted
- Date: 2026-10-24

## Context

The split-brain checker needs what each node believes: running nodes, partitions, the leader of
each replicated queue. The original function set (`PUBLISH`, `CONFIRM`, `DELIVER`, `ACK`, `DRAIN`,
`NEMESIS_START`, `NEMESIS_STOP`) has no place for it. Keeping probes in a side file would make the
history incomplete: `rmq-verify check history.jsonl` could not recompute that verdict.

## Decision

- Add one observation function, `PROBE`, whose value records the probe round, the observer node,
  the subject (a queue or `cluster`), the leader it reports, its running nodes and its partitions.
- Probes of one round are taken concurrently against every node's management API, so they can be
  compared with each other.
- `PROBE` is an observation: it is never invoked and never paired.

## Consequences

- One file holds the complete timeline: client operations, faults and cluster views.
- The schema stays at version 1 because `PROBE` was part of the first published format.
