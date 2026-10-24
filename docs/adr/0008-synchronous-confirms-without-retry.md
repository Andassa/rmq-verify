# ADR-0008: One message in flight per publisher, synchronous confirms, no retry

- Status: accepted
- Date: 2026-10-24

## Context

With asynchronous confirms, a `basic.nack` or a channel closure applies to a range of delivery
tags, and attributing the outcome to individual messages after a reconnection is error-prone.
Client-side retries make the history lie: one logical message becomes two publications.

## Decision

- Each publisher publishes one persistent (`deliveryMode=2`), mandatory message, then calls
  `waitForConfirms(timeout)`. Ack → `OK`; nack or `basic.return` → `FAIL`; timeout, I/O error,
  channel or connection closure → `INFO`.
- After any `INFO`, the connection is closed and reopened, so that a late confirm can never be
  attributed to the next message.
- Automatic recovery of the Java client is disabled; reconnection is explicit, with capped
  exponential backoff, and recorded through the outcomes it causes.
- Streams use the stream protocol with the same one-in-flight discipline. Codes produced by the
  client (confirm timeout, producer unavailable or closed, enqueue failure) are `INFO`; codes
  answered by the broker are `FAIL`.
- Throughput comes from the number of publishers (virtual threads), not from pipelining.

## Consequences

- Every outcome is attributable to exactly one message, which the checkers rely on.
- Absolute throughput is lower than a pipelined client; the tool measures guarantees, not speed.
- Because blocked publishers stop invoking, availability is measured on confirmations, not on
  invocations (METHODOLOGY 4.6).
