# Changelog

All notable changes are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-10-24

First public version.

### Added

- History model: operations, sealed values, invariant-checked histories, thread-safe recorder,
  versioned JSONL format with a strict, size-bounded reader (ADR-0003).
- Seven checkers: lost messages, duplicate deliveries, unexpected messages, FIFO ordering,
  confirmed but missing after recovery, availability and recovery time, split-brain indicators.
  Validated against a simulated correct broker with jqwik properties and PIT (ADR-0006).
- Workload: seeded AMQP 0-9-1 and stream publishers with synchronous confirms and no retry,
  manual-ack consumers, offset-tracking stream readers, drains with explicit end criteria
  (ADR-0008, ADR-0009).
- Nemeses: kill, pause, three partition shapes, latency, packet loss, client toxics, rolling
  restart; seeded planner, replayable `plan.json`, guaranteed healing (ADR-0010, ADR-0011).
- Hardened three-node cluster for RabbitMQ 3.13.7 and 4.4.0 with Toxiproxy, pinned by digest,
  optional TLS mode (ADR-0007, ADR-0016).
- Reports: Markdown, self-contained HTML, JSON, SVG charts, scenario × version matrix and
  generated `RESULTS.md` (ADR-0013).
- `minimize`: ddmin over fault plans and load reduction, with real runs as the oracle
  (ADR-0015).
- CI: quality gate, end-to-end tests on both versions, security scans (gitleaks,
  Dependency-Check, Trivy, CycloneDX SBOM), nightly chaos, release workflow.

### Fixed

- Publisher and consumer threads no longer die when the AMQP client reports a connection that is
  already closing (`ShutdownSignalException` on close), found by the first benchmark run.
- Stream drain on RabbitMQ 3.13 falls back to the last chunk id when the broker does not report a
  committed offset, so a non-empty stream is no longer recorded as empty.
- A late unflagged original delivery and a reorder of an unconfirmed publish are notes. A
  violation still requires two unflagged deliveries, a duplicated stream offset, or an inversion
  of two confirmed publishes.
- End-to-end tests start the cluster with Docker Compose. Testcontainers 2.0.5 rejects
  `container_name`, which the safety guard requires, so the suite never reached the scenarios.
- The gitleaks CI job scans the whole Git history. The previous action asked for the parent of
  the root commit on the first push and exited before scanning anything.
- Dependency-Check 13.0.0 ships `nvd.api.key=` and sends that empty value to the NVD, which
  rejects it before any analysis. The security job removes that empty default from the
  downloaded jar. A `NVD_API_KEY` secret stays optional and only makes the update faster.
- A 429 from the NVD was retried 30 times with a 0 ms delay, so the update gave up in one second.
  The security profile now waits 6 s between those attempts.

### Changed

- The README and the generated results page are in French. The numbers still come from the
  archived runs.
