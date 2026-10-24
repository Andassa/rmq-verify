# ADR-0009: The final drain is the ground truth of survival

- Status: accepted
- Date: 2026-10-24

## Context

A message that was never delivered during the run may still sit in a queue. Claiming a loss
requires knowing that the queue has been emptied after the cluster recovered, with an explicit,
checkable end criterion rather than "nothing arrived for a while".

## Decision

- After the faults are healed, the cluster is healthy and the settle delay has elapsed, regular
  consumers stop and one drain process per queue empties it.
- Destructive queues (classic, quorum): `basic.get` with manual acks until the queue reports zero
  ready messages and zero consumers, continuously, for a quiet period (3 s). The drain ends with
  `OK` only when that criterion holds; at the deadline it ends with `INFO`.
- Streams: a fresh consumer reads from the first offset until it reaches the committed offset
  reported by the stream leader; an empty stream (`NoOffsetException`) is drained at once.
- Drains are recorded like any other invocation; their deliveries are ordinary `DELIVER`
  operations of the drain process.
- `LostMessages`, the FIFO gap check and `ConfirmedButMissingAfterRecovery` only claim a loss for a
  queue whose drain ended with `OK`; otherwise they report the messages as unverifiable and the
  verdict is `UNKNOWN`.

## Consequences

- No loss is claimed on an undrained queue.
- Runs take longer (heal, health check, settle, drain), which is the price of a defensible claim.
