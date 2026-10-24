# rmq-verify run r-20261024-113035-3072

**Overall: VALID**

| Setting | Value |
|---|---|
| Scenario | classic-kill (repetition 2) |
| RabbitMQ | 3.13.7 |
| Queue type | CLASSIC |
| Seed | 20263026 |
| Started | 2026-10-24T11:30:39.161047Z |
| Operations | 19168 |
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
| 0 | kill-node | 5.0 | 10.1 | targets=[rabbit3] |
| 1 | kill-node | 21.3 | 14.0 | targets=[rabbit3] |

## Verdicts

| Checker | Verdict | Violations | Summary |
|---|---|---|---|
| Availability | VALID | 0 | 99.9% of publishes confirmed, longest unavailability 4000 ms |
| ConfirmedButMissingAfterRecovery | VALID | 0 | 0 of 2033 messages exposed to 2 fault(s) missing after recovery |
| DuplicateDeliveries | VALID | 0 | 0 message(s) delivered more than once out of 3788 |
| FifoOrdering | VALID | 0 | 0 inversion(s), 0 gap(s) over 3788 first-time deliveries |
| LostMessages | VALID | 0 | 0 lost of 3784 confirmed; 4 indeterminate (4 survived) |
| SplitBrainIndicators | VALID | 0 | 48 probe round(s), 29 outside fault windows |
| UnexpectedMessages | VALID | 0 | 0 unexpected of 3788 deliveries |

## Charts

![throughput.svg](throughput.svg)

![latency.svg](latency.svg)

![recovery.svg](recovery.svg)

## Anomalies

None.

## What this run proves

- Indeterminate: 4 publish(es) ended without a confirm; 4 of them were delivered anyway, which shows that INFO is not a failure.
- Not proven: a VALID verdict only says that this run, with this plan, did not exhibit the anomaly.
- Limits: one run of 60 s on a 3-node cluster; the seed reproduces the plan and the workload, not the broker's timing; the client reaches every node through Toxiproxy.
