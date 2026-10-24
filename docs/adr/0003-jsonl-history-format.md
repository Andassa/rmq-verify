# ADR-0003: Versioned JSONL history format

- Status: accepted
- Date: 2026-10-24

## Context

Histories must be written while the run progresses (a crash must not lose them), read back by
other tools, diffed and compressed, and parsed safely even when they come from an untrusted
source (a bug report, a CI artefact).

## Decision

- One JSON object per line. The first line is a header
  `{"kind":"header","schemaVersion":1,"runId":…,"queues":{…},"attributes":{…}}`; every following
  line is an operation `{"kind":"op",…}`.
- The reader rejects unknown schema versions, unknown fields, lines longer than 64 KiB, files
  with more than 20 million operations, and any value that does not match its function.
- Encoding and decoding use the Jackson **streaming** API. No data binding, no polymorphic type
  handling, no Java serialization: the parser cannot instantiate arbitrary classes.
- Times are stored twice: `t` (monotonic nanoseconds, used for ordering and durations) and
  `wall` (ISO-8601 instant, used for correlation with broker logs).

## Consequences

- Histories stream to disk with constant memory and compress well (`results/*.jsonl.gz`).
- A schema change increments `schemaVersion`; old readers fail loudly instead of misreading.
