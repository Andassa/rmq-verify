# ADR-0001: Java 21 and a Maven multi-module build

- Status: accepted
- Date: 2026-10-24

## Context

The verifier must talk to RabbitMQ with the official clients, model histories precisely and run
hundreds of concurrent clients on a laptop. Its quality gates (format, static analysis, coverage,
mutation testing, SBOM) must run identically on a workstation and in CI.

## Decision

- Java 21 (LTS): records and sealed interfaces model operations and values exhaustively, pattern
  matching keeps checkers readable, and virtual threads let each logical client own a blocking
  loop without a thread pool to tune. The official `amqp-client` and `stream-client` are Java.
- Maven with one module per hexagon (model, checker, workload, cluster, nemesis, report, cli),
  plus `jmh` and `e2e`. A standalone `rmq-verify-bom` aligns module versions for consumers; the
  parent POM pins every third-party version and plugin (enforced by `requirePluginVersions`).
- The Maven wrapper pins the Maven version; the enforcer requires Java 21 or newer.
- Compilation runs with `-Xlint:all -Werror` and Error Prone, so warnings fail the build.

## Consequences

- Contributors need JDK 21. Newer JDKs build with `--release 21`.
- Module boundaries are visible in the POMs, and ArchUnit checks them at the package level.
