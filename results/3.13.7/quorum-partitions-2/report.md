# rmq-verify run r-20261024-114009-3842

**Overall: VALID**

| Setting | Value |
|---|---|
| Scenario | quorum-partitions (repetition 2) |
| RabbitMQ | 3.13.7 |
| Queue type | QUORUM |
| Seed | 20265026 |
| Started | 2026-10-24T11:40:13.343928Z |
| Operations | 20799 |
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
| 0 | partition-isolate | 5.0 | 12.5 | targets=[rabbit2] |
| 1 | partition-bridge | 26.2 | 10.0 | targets=[rabbit3, rabbit2, rabbit1] left=1 |

## Verdicts

| Checker | Verdict | Violations | Summary |
|---|---|---|---|
| Availability | VALID | 0 | 100.0% of publishes confirmed, longest unavailability 1000 ms |
| ConfirmedButMissingAfterRecovery | VALID | 0 | 0 of 0 messages exposed to 2 fault(s) missing after recovery |
| DuplicateDeliveries | VALID | 0 | 0 message(s) delivered more than once out of 4123 |
| FifoOrdering | VALID | 0 | 0 inversion(s), 0 gap(s) over 4123 first-time deliveries |
| LostMessages | VALID | 0 | 0 lost of 4122 confirmed; 1 indeterminate (1 survived) |
| SplitBrainIndicators | VALID | 0 | 31 probe round(s), 20 outside fault windows |
| UnexpectedMessages | VALID | 0 | 0 unexpected of 4123 deliveries |

## Charts

![throughput.svg](throughput.svg)

![latency.svg](latency.svg)

![recovery.svg](recovery.svg)

## Anomalies

None.

## What this run proves

- Indeterminate: 1 publish(es) ended without a confirm; 1 of them were delivered anyway, which shows that INFO is not a failure.
- Not proven: a VALID verdict only says that this run, with this plan, did not exhibit the anomaly.
- Limits: one run of 60 s on a 3-node cluster; the seed reproduces the plan and the workload, not the broker's timing; the client reaches every node through Toxiproxy.
