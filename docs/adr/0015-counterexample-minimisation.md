# ADR-0015: Counterexample minimisation with ddmin over real runs

- Status: accepted
- Date: 2026-10-24

## Context

A failing run with eight faults and four publishers is hard to analyse. Knowing that a single
`kill-node` reproduces the anomaly, or that no fault is needed at all, is the useful answer.

## Decision

- `DeltaDebugger` implements Zeller and Hildebrandt's `ddmin` on any list, with memoised oracle
  answers; it returns a 1-minimal failing subsequence and the number of distinct candidates. It
  first tries the empty input, because "it fails without any fault" is the most informative
  result. It is pure and property-tested (result fails, is a subsequence, is 1-minimal).
- `rmq-verify minimize --plan plan.json --checker X --repeats N` uses real runs as the oracle: a
  candidate fails if at least one of N runs makes checker X return `INVALID`. Faults are reduced
  first (ddmin over the plan events, original timing kept), then the load: publishers are halved
  while the failure persists, and the run is shortened to the end of the last kept fault plus ten
  seconds when that still fails.
- The result is written as `minimized-plan.json` with the exact `replay` command.

## Consequences

- Minimisation costs real time: about `candidates × repeats` runs. The `repeats` parameter trades
  time against the risk of discarding a candidate whose failure is intermittent.
- A minimal plan is minimal for the observed runs, not a proof that fewer faults can never fail.
