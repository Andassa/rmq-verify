# ADR-0002: Hexagonal architecture enforced by ArchUnit

- Status: accepted
- Date: 2026-10-24

## Context

The value of the project lies in its checkers. If they depended on Docker, AMQP or the file
system, a verdict could not be recomputed offline, and a checker bug could hide behind an
infrastructure bug.

## Decision

- `rmq-verify-model` holds the domain and depends only on the JDK. Its `jsonl` package is the
  single place allowed to use `jackson-core` (streaming API only, no data binding).
- `rmq-verify-checker` depends only on the model. Checkers are pure functions of a history.
- Infrastructure libraries (AMQP, stream client, process execution, HTTP to Toxiproxy and the
  management API) are confined to `workload`, `cluster` and `nemesis`.
- ArchUnit tests in each module fail the build on any violation, including package cycles.

## Consequences

- `rmq-verify check history.jsonl` reproduces any verdict without a cluster.
- Adapters are tested with fakes of narrow ports (`ProcessRunner`, `ClusterControl`), which keeps
  their unit tests fast; real behaviour is covered by the e2e module.
