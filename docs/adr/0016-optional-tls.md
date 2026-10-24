# ADR-0016: Optional TLS mode with throwaway certificates

- Status: accepted
- Date: 2026-10-24

## Context

Production clusters use TLS; the client code paths (handshakes, reconnections through a cut proxy,
stream TLS with advertised hosts) deserve the same fault injection. Shipping certificates in the
repository would be a secret leak waiting to happen.

## Decision

- `make up TLS=1` runs `deploy/scripts/gen-test-certs.sh`, which creates a CA and one broker
  certificate (P-256, 30 days, SAN `rabbit1..3`, `localhost`, `127.0.0.1`) under the Git-ignored
  `deploy/tls/generated/`.
- `deploy/docker-compose.tls.yml` mounts the certificates and a `conf.d` assembled from the base
  configuration without plain listeners, plus `listeners.ssl.default = 5671` and
  `stream.listeners.ssl.1 = 5551` (TLS 1.3 and 1.2). Toxiproxy forwards the same local ports to
  the TLS listeners, so every nemesis works unchanged.
- The client trusts only that CA (`--tls-ca`), verifies the server host name for AMQP and uses a
  Netty context for streams. Nothing is ever "trust everything".

## Consequences

- TLS mode validates the TLS code paths; its unencrypted keys make it unsuitable as a deployment
  recipe, which the threat model states.
