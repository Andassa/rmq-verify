# ADR-0004: `INFO` is an indeterminate outcome, never a failure

- Status: accepted
- Date: 2026-10-24

## Context

When a connection breaks between `basic.publish` and the publisher confirm, the client cannot
know whether the broker persisted the message. Many ad-hoc tests count such publishes as
failures, then report the survivors as "phantom" messages, or count them as successes and report
"losses" that never were guarantees.

## Decision

- Completions are `OK` (definitive success), `FAIL` (definitive negative answer from the broker:
  `basic.nack` or a mandatory return) or `INFO` (unknown: timeout, connection or channel closed,
  I/O error after the publish was attempted).
- An invocation without completion, for example because the client crashed, is treated as
  `INFO` by every checker.
- Checkers never raise a violation from an `INFO` message alone. They report how many
  indeterminate messages survived and how many did not.
- Publishers never retry a message after an `INFO`: a retry would create a second publication
  with the same payload and hide the very behaviour under test.

## Consequences

- Loss is only ever claimed for messages the broker explicitly confirmed.
- Reports show indeterminate counts next to verdicts, so readers see the size of the grey zone.
