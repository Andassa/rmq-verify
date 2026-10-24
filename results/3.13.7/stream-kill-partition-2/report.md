# rmq-verify run r-20261024-123511-4012

**Overall: VALID**

| Setting | Value |
|---|---|
| Scenario | stream-kill-partition (repetition 2) |
| RabbitMQ | 3.13.7 |
| Queue type | STREAM |
| Seed | 20267026 |
| Started | 2026-10-24T12:35:14.728554Z |
| Operations | 18711 |
| confirmTimeoutMs | 5000 |
| consumersPerQueue | 1 |
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
| 0 | partition-halves | 5.0 | 13.2 | targets=[rabbit1] |
| 1 | kill-node | 24.1 | 10.9 | targets=[rabbit1] |

## Verdicts

| Checker | Verdict | Violations | Summary |
|---|---|---|---|
| Availability | VALID | 0 | 99.8% of publishes confirmed, longest unavailability 12000 ms |
| ConfirmedButMissingAfterRecovery | VALID | 0 | 0 of 1197 messages exposed to 2 fault(s) missing after recovery |
| DuplicateDeliveries | VALID | 0 | 1 message(s) delivered more than once out of 4632 |
| FifoOrdering | VALID | 0 | 0 inversion(s), 0 gap(s) over 9256 first-time deliveries |
| LostMessages | VALID | 0 | 0 lost of 4624 confirmed; 8 indeterminate (4 survived) |
| SplitBrainIndicators | VALID | 0 | 36 probe round(s), 20 outside fault windows |
| UnexpectedMessages | VALID | 0 | 0 unexpected of 9257 deliveries |

## Charts

![throughput.svg](throughput.svg)

![latency.svg](latency.svg)

![recovery.svg](recovery.svg)

## Anomalies

### DuplicateDeliveries

- **OFFSET_REPLAY** (NOTE): publisher-0#315 in rmqv.r-20261024-123511-4012.stream.0 read 3 times, replayed by the same reader at one offset [evidence: #3636 consumer-0-0 OK DELIVER @24.125s; #3834 consumer-0-0 OK DELIVER @25.187s; #15282 drain:rmqv.r-20261024-123511-4012.stream.0 OK DELIVER @87.169s]

### SplitBrainIndicators

- **LEADER_CONFLICT** (NOTE): round 7: rmqv.r-20261024-123511-4012.stream.0 has leaders [rabbit1, rabbit3] during a fault [evidence: #3640 prober OK PROBE @24.182s; #3642 prober OK PROBE @24.182s]

## What this run proves

- Indeterminate: 8 publish(es) ended without a confirm; 4 of them were delivered anyway, which shows that INFO is not a failure.
- Not proven: a VALID verdict only says that this run, with this plan, did not exhibit the anomaly.
- Limits: one run of 60 s on a 3-node cluster; the seed reproduces the plan and the workload, not the broker's timing; the client reaches every node through Toxiproxy.
