# rmq-verify run r-20261024-120555-3842

**Overall: VALID**

| Setting | Value |
|---|---|
| Scenario | quorum-partitions (repetition 2) |
| RabbitMQ | 4.4.0 |
| Queue type | QUORUM |
| Seed | 20265026 |
| Started | 2026-10-24T12:06:01.480098Z |
| Operations | 15188 |
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
| Availability | VALID | 0 | 100.0% of publishes confirmed, longest unavailability 0 ms |
| ConfirmedButMissingAfterRecovery | VALID | 0 | 0 of 0 messages exposed to 2 fault(s) missing after recovery |
| DuplicateDeliveries | VALID | 0 | 0 message(s) delivered more than once out of 3000 |
| FifoOrdering | VALID | 0 | 0 inversion(s), 0 gap(s) over 3000 first-time deliveries |
| LostMessages | VALID | 0 | 0 lost of 2999 confirmed; 1 indeterminate (1 survived) |
| SplitBrainIndicators | VALID | 0 | 32 probe round(s), 22 outside fault windows |
| UnexpectedMessages | VALID | 0 | 0 unexpected of 3000 deliveries |

## Charts

![throughput.svg](throughput.svg)

![latency.svg](latency.svg)

![recovery.svg](recovery.svg)

## Anomalies

### SplitBrainIndicators

- **VIEW_DIVERGENCE** (NOTE): round 6: 3 different running-node sets during a fault [evidence: #6891 prober OK PROBE @25.296s; #6893 prober OK PROBE @25.296s; #6895 prober OK PROBE @25.296s]

## What this run proves

- Indeterminate: 1 publish(es) ended without a confirm; 1 of them were delivered anyway, which shows that INFO is not a failure.
- Not proven: a VALID verdict only says that this run, with this plan, did not exhibit the anomaly.
- Limits: one run of 60 s on a 3-node cluster; the seed reproduces the plan and the workload, not the broker's timing; the client reaches every node through Toxiproxy.
