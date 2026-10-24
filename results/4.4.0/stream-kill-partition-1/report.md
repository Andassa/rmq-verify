# rmq-verify run r-20261024-121201-4011

**Overall: INVALID**

| Setting | Value |
|---|---|
| Scenario | stream-kill-partition (repetition 1) |
| RabbitMQ | 4.4.0 |
| Queue type | STREAM |
| Seed | 20267025 |
| Started | 2026-10-24T12:12:16.127345Z |
| Operations | 14571 |
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
| 0 | partition-halves | 5.0 | 13.1 | targets=[rabbit1] |
| 1 | kill-node | 25.8 | 10.6 | targets=[rabbit3] |
| 2 | partition-halves | 41.8 | 9.6 | targets=[rabbit2] |

## Verdicts

| Checker | Verdict | Violations | Summary |
|---|---|---|---|
| Availability | VALID | 0 | 99.9% of publishes confirmed, longest unavailability 13000 ms |
| ConfirmedButMissingAfterRecovery | VALID | 0 | 0 of 3322 messages exposed to 3 fault(s) missing after recovery |
| DuplicateDeliveries | INVALID | 1 | 2 message(s) delivered more than once out of 3601 |
| FifoOrdering | VALID | 0 | 0 inversion(s), 0 gap(s) over 7202 first-time deliveries |
| LostMessages | VALID | 0 | 0 lost of 3597 confirmed; 4 indeterminate (4 survived) |
| SplitBrainIndicators | VALID | 0 | 30 probe round(s), 12 outside fault windows |
| UnexpectedMessages | VALID | 0 | 0 unexpected of 7205 deliveries |

## Charts

![throughput.svg](throughput.svg)

![latency.svg](latency.svg)

![recovery.svg](recovery.svg)

## Anomalies

### DuplicateDeliveries

- **DUPLICATE_IN_LOG** (VIOLATION): publisher-2#534 in rmqv.r-20261024-121201-4011.stream.0 read 4 times at 2 distinct offsets [evidence: #6169 consumer-0-0 OK DELIVER @26.783s; #6173 consumer-0-0 OK DELIVER @26.783s; #13005 drain:rmqv.r-20261024-121201-4011.stream.0 OK DELIVER @90.651s; #13009 drain:rmqv.r-20261024-121201-4011.stream.0 OK DELIVER @90.651s]
- **OFFSET_REPLAY** (NOTE): publisher-3#518 in rmqv.r-20261024-121201-4011.stream.0 read 3 times, replayed by the same reader at one offset [evidence: #6150 consumer-0-0 OK DELIVER @25.663s; #6168 consumer-0-0 OK DELIVER @26.783s; #13004 drain:rmqv.r-20261024-121201-4011.stream.0 OK DELIVER @90.651s]

## What this run proves

- Proven: DuplicateDeliveries found 1 violation(s); the evidence operations are in history.jsonl and `rmq-verify check` reproduces them.
- Indeterminate: 4 publish(es) ended without a confirm; 4 of them were delivered anyway, which shows that INFO is not a failure.
- Not proven: a VALID verdict only says that this run, with this plan, did not exhibit the anomaly.
- Limits: one run of 60 s on a 3-node cluster; the seed reproduces the plan and the workload, not the broker's timing; the client reaches every node through Toxiproxy.
