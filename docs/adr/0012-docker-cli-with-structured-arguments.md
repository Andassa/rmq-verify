# ADR-0012: Docker CLI with structured arguments rather than a Docker Java client

- Status: accepted
- Date: 2026-10-24

## Context

The cluster module needs about ten Docker operations (inspect, kill, start, stop, pause, unpause,
exec, network inspect). Java Docker clients bring a large dependency tree (HTTP stacks, JNA, Unix
socket transports) and must be configured for each runtime (Docker Desktop, Colima, rootless).

## Decision

- Invoke the `docker` binary through `ProcessBuilder`, never through a shell. Each argument is a
  separate list element; container, network, proxy and toxic names are validated against
  `[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}` before any command is built, so a value can neither start an
  option nor be interpreted by a shell.
- `SystemProcessRunner` is the only class allowed to start processes (ArchUnit), it bounds the
  captured output and kills the process after a timeout.
- Everything else depends on the `ProcessRunner` port, which unit tests replace with a scripted
  fake.
- Toxiproxy and the RabbitMQ management API are reached with `java.net.http`.

## Consequences

- No extra dependency, and whatever `docker` context the user configured is honoured.
- FindSecBugs reports `COMMAND_INJECTION` on the runner; the exclusion is scoped to that class and
  justified by the validation above (`config/spotbugs/exclude.xml`).
