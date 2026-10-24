# ADR-0010: Seeded fault plans, replay, and the limits of determinism

- Status: accepted
- Date: 2026-10-24

## Context

A counterexample is only useful if it can be replayed. A real distributed system is not
deterministic: Raft elections, TCP retransmissions and thread scheduling vary between runs.

## Decision

- One seed drives both the workload (`PublisherPlan`: queue choice, sequence, UUID, size, pause per
  publisher, through `SplittableRandom.split()`) and the fault plan (`FaultPlanner`).
- Every random choice of a nemesis (targets, latency, loss rate, partition sides) is made at
  planning time and stored in `plan.json`. `inject` and `heal` are deterministic functions of the
  stored event, so `rmq-verify replay --plan plan.json` executes exactly the same faults.
- Plans are serialised with sorted keys: the same seed and options give the same bytes (property
  test `sameSeedGivesTheSamePlanBytes`).
- Replayed plans are untrusted input: size limit, unknown fields rejected, record validation,
  registered nemesis names only.
- Reproducibility of an anomaly is measured, not assumed: scenarios are repeated N times with the
  same seed and reports state how many repetitions exhibited it.

## Consequences

- What is reproducible: the schedule, targets and parameters of the faults, and the client
  workload. What is not: the broker's reaction. The README and every report say so.
