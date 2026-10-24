# ADR-0011: iptables for partitions, tc netem for degraded links, Toxiproxy for clients

- Status: accepted
- Date: 2026-10-24

## Context

Faults happen at two boundaries: between brokers (Erlang distribution, Raft, Khepri/Mnesia) and
between clients and brokers (AMQP, stream protocol). One mechanism cannot model both cleanly.

## Decision

- **Inter-node partitions**: `iptables` DROP rules inside the node containers, in a dedicated
  `RMQV` chain hooked into INPUT and OUTPUT, so that healing is a flush of that chain only. DROP
  (not REJECT) makes peers detect the partition through timeouts, like a real cable cut.
- **Latency and packet loss on a node**: `tc qdisc replace dev eth0 root netem`, removed with
  `tc qdisc del`. Healing tolerates "no such qdisc".
- **Client faults**: Toxiproxy sits between the client and every AMQP and stream listener.
  `client-toxic` either disables a proxy (connections drop) or adds latency (confirms time out).
  Toxiproxy cannot partition brokers from each other, which is why iptables is still needed.
- **Process faults**: `docker kill --signal KILL`, `docker pause` (cgroup freezer, a SIGSTOP
  equivalent that cannot be caught) and `docker stop`/`start` for rolling restarts.
- The client is outside every partition: it reaches all nodes through Toxiproxy. This is a
  stronger client than an application living on one side, and it is stated in the methodology.

## Consequences

- Nodes need `NET_ADMIN`, granted to those containers only (ADR-0007).
- Commands run through `docker exec` with structured arguments (ADR-0012).
