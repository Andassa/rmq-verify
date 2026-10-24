# rmq-verify run r-20261024-120807-3c29

**Overall: VALID**

| Setting | Value |
|---|---|
| Scenario | quorum-network (repetition 1) |
| RabbitMQ | 4.4.0 |
| Queue type | QUORUM |
| Seed | 20266025 |
| Started | 2026-10-24T12:08:25.963466Z |
| Operations | 13589 |
| confirmTimeoutMs | 5000 |
| consumersPerQueue | 2 |
| durationSeconds | 60 |
| maxLength | 0 |
| payloadBytes | 512 |
| publishers | 4 |
| queues | 1 |
| ratePerPublisher | 30.0 |
| settleSeconds | 20 |

## Fault plan

| # | Nemesis | Start (s) | Duration (s) | Parameters |
|---|---|---|---|---|
| 0 | packet-loss | 5.0 | 10.9 | targets=[rabbit3] lossPercent=12 |
| 1 | network-latency | 21.8 | 14.4 | targets=[rabbit1] delayMs=380 jitterMs=95 |
| 2 | client-toxic | 41.4 | 12.7 | targets=[rabbit1] latencyMs=1594 mode=cut |

## Verdicts

| Checker | Verdict | Violations | Summary |
|---|---|---|---|
| Availability | VALID | 0 | 100.0% of publishes confirmed, longest unavailability 1000 ms |
| ConfirmedButMissingAfterRecovery | VALID | 0 | 0 of 0 messages exposed to 3 fault(s) missing after recovery |
| DuplicateDeliveries | VALID | 0 | 0 message(s) delivered more than once out of 2673 |
| FifoOrdering | VALID | 0 | 0 inversion(s), 0 gap(s) over 2673 first-time deliveries |
| LostMessages | VALID | 0 | 0 lost of 2673 confirmed; 0 indeterminate (0 survived) |
| SplitBrainIndicators | VALID | 0 | 36 probe round(s), 16 outside fault windows |
| UnexpectedMessages | VALID | 0 | 0 unexpected of 2673 deliveries |

## Charts

![throughput.svg](throughput.svg)

![latency.svg](latency.svg)

![recovery.svg](recovery.svg)

## Anomalies

None.

## What this run proves

- Not proven: a VALID verdict only says that this run, with this plan, did not exhibit the anomaly.
- Limits: one run of 60 s on a 3-node cluster; the seed reproduces the plan and the workload, not the broker's timing; the client reaches every node through Toxiproxy.
