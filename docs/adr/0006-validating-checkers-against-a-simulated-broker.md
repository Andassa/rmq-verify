# ADR-0006: Checkers are validated against a simulated correct broker

- Status: accepted
- Date: 2026-10-24

## Context

A checker that never fires proves nothing, and a checker that fires on correct behaviour drowns
real anomalies. Example-based tests only cover the cases their author thought of.

## Decision

- The checker tests contain a seeded simulation of a broker that honours every guarantee under
  test: confirmed messages are persisted, consumers take from the head of the queue, lost acks
  lead to flagged redeliveries, streams are read by offset, drains empty everything, faults are
  healed and probes agree once healed.
- Property 1 (jqwik): every generated history is `VALID` for every registered checker.
- Properties 2 to 8: one mutation per checker (drop deliveries, duplicate a delivery, inject an
  unknown message, swap two deliveries, drop post-heal deliveries, delay recovery, disagree on a
  leader) must be detected by that checker, and the anomaly must cite the mutated operations.
- PIT mutation testing (at least 80 %) checks that the tests constrain the implementation.

## Consequences

- False positives and false negatives are both tested on hundreds of generated runs per build.
- The simulator is test code; it is not used to produce any result in `RESULTS.md`.
