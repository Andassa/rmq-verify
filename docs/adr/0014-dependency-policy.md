# ADR-0014: Dependency policy

- Status: accepted
- Date: 2026-10-24

## Context

The tool drives Docker and holds cluster credentials; every dependency is code running with that
power. Reproducible builds and results also require stable versions.

## Decision

- Every version is pinned in the parent POM; the enforcer's `requirePluginVersions` fails builds
  that rely on default plugin versions. Container images are pinned by digest in
  `deploy/images.env`; GitHub Actions by commit SHA.
- Runtime dependencies are limited to: the official RabbitMQ clients (`amqp-client`,
  `stream-client`, plus `netty-handler` aligned with the stream client for TLS contexts),
  `jackson-core` (model) and `jackson-databind` (plans, reports, management API), `picocli`,
  `slf4j` with `logback` and the Logstash encoder (JSON logs). Docker is driven through its CLI
  and HTTP through `java.net.http` instead of adding clients (ADR-0012).
- Test-only: JUnit, AssertJ, jqwik, Mockito (only for the broker client interfaces), ArchUnit,
  JMH. End-to-end tests start `deploy/docker-compose.yml` with the Docker CLI. Testcontainers
  2.0.5 rejects `container_name`, which the safety guard and the scripts require.
- Updates arrive through Dependabot (Maven, Actions, Dockerfile) and must pass `make verify`.
  OWASP Dependency-Check fails the security workflow at CVSS 7; Trivy reports image findings;
  each release ships a CycloneDX SBOM.
- google-java-format is pinned to 1.36.1: 1.37 needs `commonmark` on the formatter class path,
  which Spotless 3.10 does not provide.

## Consequences

- Adding a dependency requires an ADR update and a justification in the pull request.
