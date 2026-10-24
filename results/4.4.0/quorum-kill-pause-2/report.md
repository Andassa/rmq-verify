# rmq-verify run r-20261024-120159-345a

**Overall: VALID**

| Setting | Value |
|---|---|
| Scenario | quorum-kill-pause (repetition 2) |
| RabbitMQ | 4.4.0 |
| Queue type | QUORUM |
| Seed | 20264026 |
| Started | 2026-10-24T12:02:12.548114Z |
| Operations | 23348 |
| confirmTimeoutMs | 5000 |
| consumersPerQueue | 0 |
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
| 0 | kill-node | 5.0 | 8.5 | targets=[rabbit3] |
| 1 | pause-node | 19.2 | 8.1 | targets=[rabbit3] |
| 2 | kill-node | 34.4 | 14.8 | targets=[rabbit1] |

## Verdicts

| Checker | Verdict | Violations | Summary |
|---|---|---|---|
| Availability | VALID | 0 | 99.8% of publishes confirmed, longest unavailability 5000 ms |
| ConfirmedButMissingAfterRecovery | VALID | 0 | 0 of 2931 messages exposed to 3 fault(s) missing after recovery |
| DuplicateDeliveries | VALID | 0 | 0 message(s) delivered more than once out of 4630 |
| FifoOrdering | VALID | 0 | 0 inversion(s), 0 gap(s) over 4628 first-time deliveries |
| LostMessages | VALID | 0 | 0 lost of 4623 confirmed; 7 indeterminate (5 survived) |
| SplitBrainIndicators | VALID | 0 | 42 probe round(s), 13 outside fault windows |
| UnexpectedMessages | VALID | 0 | 0 unexpected of 4628 deliveries |

## Charts

![throughput.svg](throughput.svg)

![latency.svg](latency.svg)

![recovery.svg](recovery.svg)

## Anomalies

None.

## What this run proves

- Indeterminate: 7 publish(es) ended without a confirm; 5 of them were delivered anyway, which shows that INFO is not a failure.
- Not proven: a VALID verdict only says that this run, with this plan, did not exhibit the anomaly.
- Limits: one run of 60 s on a 3-node cluster; the seed reproduces the plan and the workload, not the broker's timing; the client reaches every node through Toxiproxy.
