# rmq-verify run r-20261024-113521-345a

**Overall: VALID**

| Setting | Value |
|---|---|
| Scenario | quorum-kill-pause (repetition 2) |
| RabbitMQ | 3.13.7 |
| Queue type | QUORUM |
| Seed | 20264026 |
| Started | 2026-10-24T11:35:27.362139Z |
| Operations | 14837 |
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
| Availability | VALID | 0 | 99.7% of publishes confirmed, longest unavailability 10000 ms |
| ConfirmedButMissingAfterRecovery | VALID | 0 | 0 of 2014 messages exposed to 3 fault(s) missing after recovery |
| DuplicateDeliveries | VALID | 0 | 0 message(s) delivered more than once out of 2931 |
| FifoOrdering | VALID | 0 | 0 inversion(s), 0 gap(s) over 2929 first-time deliveries |
| LostMessages | VALID | 0 | 0 lost of 2923 confirmed; 8 indeterminate (6 survived) |
| SplitBrainIndicators | VALID | 0 | 45 probe round(s), 19 outside fault windows |
| UnexpectedMessages | VALID | 0 | 0 unexpected of 2929 deliveries |

## Charts

![throughput.svg](throughput.svg)

![latency.svg](latency.svg)

![recovery.svg](recovery.svg)

## Anomalies

### FifoOrdering

- **REORDERED_UNCONFIRMED** (NOTE): drain:rmqv.r-20261024-113521-345a.quorum.0 received publisher-2#551 after #552 in rmqv.r-20261024-113521-345a.quorum.0, at least one of them unconfirmed [evidence: #12097 drain:rmqv.r-20261024-113521-345a.quorum.0 OK DELIVER @146.707s; #12109 drain:rmqv.r-20261024-113521-345a.quorum.0 OK DELIVER @146.743s]

## What this run proves

- Indeterminate: 8 publish(es) ended without a confirm; 6 of them were delivered anyway, which shows that INFO is not a failure.
- Not proven: a VALID verdict only says that this run, with this plan, did not exhibit the anomaly.
- Limits: one run of 60 s on a 3-node cluster; the seed reproduces the plan and the workload, not the broker's timing; the client reaches every node through Toxiproxy.
