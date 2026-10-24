# ADR-0007: Test cluster deployment and secrets

- Status: accepted
- Date: 2026-10-24

## Context

The verifier needs a disposable three-node cluster whose nodes can be killed, paused and
partitioned. The official image enables `guest` from any host, ships no `iptables` and runs the
broker as root until its entrypoint drops privileges. Credentials must never reach Git.

## Decision

- Docker Compose with one service per node (`rabbit1..3`), classic peer discovery and
  `pause_minority`. `rabbit2` and `rabbit3` start once `rabbit1` is healthy, which avoids
  concurrent cluster formation.
- Node image = pinned official image (`deploy/images.env`, index digests) + `iptables` and
  `iproute2`. The broker runs as `rabbitmq` with a read-only root file system, `cap_drop: ALL`,
  `no-new-privileges` and only `NET_ADMIN`, used by `docker exec -u 0` for `iptables` and `tc`.
- `deploy/rabbitmq/conf.d` replaces the image defaults (`loopback_users.guest = true`).
- `make up` generates `.env` (mode 600, Git-ignored) with random admin and application passwords
  and Erlang cookie. The application user only gets `rmqv.*` queues on vhost `rmqv`, plus write
  on `amq.default`; it has no management tag. Its password is passed to `rabbitmqctl` on stdin,
  never on a command line.
- Management APIs and Toxiproxy listen on `127.0.0.1` only. Every container, and the dedicated
  network `rmqv-net`, carries the label `io.github.andassa.rmq-verify.cluster=rmqv`, which the
  nemesis safety guard checks before any injection.

## Consequences

- The Erlang cookie is visible in the process arguments inside the containers; acceptable for a
  disposable local cluster, documented in the threat model.
- Switching versions (`make up RABBITMQ=3.13`) requires `make down` first, because the data
  volumes of one version are not reused by the other.
