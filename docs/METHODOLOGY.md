# Methodology

This document defines precisely what `rmq-verify` observes, what it claims and what it cannot
claim. Every checker implements one of the definitions below; the code references the section
numbers.

## 1. Consistency model under test

RabbitMQ does not offer linearizable queues. What it documents, and what we test, is:

- **Durability of confirmed messages**: once a publisher confirm (`basic.ack`) is received for a
  persistent message routed to a durable queue, the message must survive any failure that the
  queue type claims to tolerate (a minority of nodes for quorum queues and streams; nothing
  beyond a clean restart for classic queues, which are not replicated in 4.x).
- **At-least-once delivery** with manual acknowledgements: a message may be delivered more than
  once, but every redelivery must carry the `redelivered` flag (AMQP 0-9-1 queues).
- **Per-publisher FIFO** inside one queue, for messages that are delivered for the first time.

## 2. Histories

A history `H = o₁ … oₙ` is the sequence of operations recorded during a run. Each operation has
an identifier, a logical process, a type `INVOKE | OK | FAIL | INFO`, a function, a value, a
monotonic timestamp `t(o)` and a wall-clock time. Identifiers and timestamps are assigned under a
single lock, so `i < j ⇒ t(oᵢ) ≤ t(oⱼ)`.

Two kinds of functions exist:

- **Invocations** (`PUBLISH → CONFIRM`, `ACK`, `DRAIN`): an `INVOKE` followed by at most one
  completion of type `OK`, `FAIL` or `INFO` from the same process. A publish completes with a
  `CONFIRM` operation. An invocation without completion is *pending*.
- **Observations** (`DELIVER`, `NEMESIS_START`, `NEMESIS_STOP`, `PROBE`): single operations that
  record something the process saw. They are never of type `INVOKE`.

A history is rejected at construction if a completion has no matching pending invocation, if a
publisher reuses or decreases a sequence number in a queue, if values do not match their
function, or if identifiers or timestamps are not monotonic.

### 2.1 Indeterminate outcomes

`INFO` means *we do not know*. A publish whose connection dropped before the confirm arrived may
or may not have been persisted. Pending invocations are treated as `INFO`. An indeterminate
message that is never delivered is **not** an anomaly; an indeterminate message that is delivered
proves that it was persisted. Treating `INFO` as failure would turn every network blip into a
false "phantom message" report; treating it as success would turn it into a false "lost
message" report.

## 3. Notation

For a message `m` (identified by its UUID):

- `pub(m)`: the `INVOKE PUBLISH` operation; `q(m)` its queue; `seq(m)` its sequence number for
  its publisher and queue.
- `conf(m) ∈ {OK, FAIL, INFO}`: the type of its `CONFIRM` completion, `INFO` if pending.
- `D(m)`: deliveries to regular consumers; `Dᵈ(m)`: deliveries to the drain process of `q(m)`.
- `drained(q)`: true iff the drain of `q` completed with `OK` (its end criterion was met).
- Fault windows `Wₖ = [sₖ, eₖ]` from matching `NEMESIS_START` / `NEMESIS_STOP` operations.
- A queue is **destructive** (classic, quorum: consuming removes) or a **log** (stream:
  consuming reads without removing; the drain re-reads the whole log from the first offset).

## 4. Anomalies

### 4.1 Lost confirmed message (`LostMessages`)

`m` is **lost** iff `conf(m) = OK ∧ D(m) ∪ Dᵈ(m) = ∅ ∧ drained(q(m))`.

If `drained(q(m))` is false, the message is reported as *unverifiable* and the verdict cannot be
`VALID`. Indeterminate messages are counted separately as *survived* (delivered) or *not
delivered*; neither is a violation.

### 4.2 Duplicate delivery (`DuplicateDeliveries`)

Let `δ(m)` be the deliveries of `m` ordered by time, restricted to `D(m) ∪ Dᵈ(m)` for destructive
queues. For each `dᵢ`, `i ≥ 2`:

- *legitimate redelivery* if `dᵢ.redelivered` (the broker did not get, or lost, the ack);
- *unexpected duplicate* otherwise: the broker handed out the same message twice as if it were
  new, which means the queue stored it twice or forgot a delivery.

For log queues the drain re-reads everything, so `D(m)` and `Dᵈ(m)` are judged separately: two
occurrences of `m` within `Dᵈ(m)` mean the log contains `m` twice (unexpected duplicate);
repeated deliveries of `m` to the same regular consumer are offset replays after consumer
recovery (legitimate, at-least-once).

### 4.3 Unexpected message (`UnexpectedMessages`)

A delivery `d` is unexpected iff its payload cannot be decoded, or its UUID was never published
in this history, or its decoded fields (publisher, queue, sequence) differ from the publication
with the same UUID. A message from another run identifier is reported as a note (residue of a
previous run) and does not invalidate the run. A delivery of a message whose confirm was `FAIL`
is reported as a note: a negative confirm says the broker did not take responsibility, not that
the message is guaranteed absent.

### 4.4 FIFO violation (`FifoOrdering`)

Let `F(c, P, q)` be the deliveries to consumer process `c` of messages of publisher `P` in queue
`q`, in delivery order, excluding deliveries flagged `redelivered` and deliveries of a UUID that
`c` already received. Then:

- **inversion**: `dᵢ, dⱼ ∈ F(c, P, q), i < j, seq(dᵢ) > seq(dⱼ)`;
- **gap**: a confirmed `m` of `(P, q)` is never delivered while some `m'` of `(P, q)` with
  `seq(m') > seq(m)` is delivered. A gap is the ordering signature of a loss in the middle of a
  queue: the successors survived, the message did not. Gaps overlap with 4.1 on purpose, they
  locate the loss in the sequence.

Competing consumers receive disjoint sub-sequences of one queue, so per-consumer monotonicity is
the strongest claim that holds with several consumers.

### 4.5 Confirmed but missing after recovery (`ConfirmedButMissingAfterRecovery`)

For `m` with `conf(m) = OK` at time `t_c`, let `W = [s, e]` be the first fault window with
`s > t_c`. `m` is *expected after recovery* iff it is in a log queue, or no `ACK OK` of `m`
happened before `e`. It is **missing after recovery** iff it is expected and no delivery of `m`
occurs after `e`. If the window never closed, the message is unverifiable. This isolates the
losses that faults caused from losses that happen in steady state.

### 4.6 Availability (`Availability`)

Time is cut into windows of fixed width (1 s by default). For each window, the success rate is
the number of `CONFIRM OK` divided by the number of publishes invoked in that window. An
*unavailability* is a maximal run of windows with invocations and no success. The **recovery
time** of a fault is `t(first CONFIRM OK of a publish invoked after e) − e`. The verdict is
`INVALID` if a fault never recovers or if a recovery exceeds the configured bound (60 s by
default); availability is not a RabbitMQ guarantee, the bound is the operator's expectation.

### 4.7 Split-brain indicators (`SplitBrainIndicators`)

Probes query each node's management API for its view: running nodes, partitions and the leader
of each replicated queue. Within one probe round:

- two nodes naming different leaders for the same queue is a *leader conflict*;
- disagreement on the running node set is a *view divergence*;
- a node reporting partitions is a *partition report*.

During fault windows these are notes: divergent views are expected under partition. After every
fault has been healed and the settle delay has elapsed, any of them is a violation.

## 5. Verdicts

Each checker returns `VALID`, `INVALID` or `UNKNOWN`. `INVALID` requires at least one violation
with evidence (operation identifiers). `UNKNOWN` means the history does not allow a conclusion
(empty history, drain not completed, fault window never closed). Notes never change a verdict
on their own.

## 6. What is proven and what is not

- A violation is a proof: the evidence operations are in `history.jsonl` and `rmq-verify check`
  reproduces the verdict offline.
- `VALID` is not a proof of correctness. It says that this run, with this plan, did not exhibit
  the anomaly. Confidence comes from repetitions and from the diversity of fault plans.
- **Determinism is limited.** The seed makes the fault plan and the workload reproducible, not
  the behaviour of a real distributed system: thread scheduling, Raft elections and TCP timing
  differ between runs. We therefore repeat each scenario N times with the same seed and report
  how many repetitions exhibited each anomaly.
- The client sits outside the partitions (it reaches every node through Toxiproxy), which is a
  stronger client than a real application deployed on one side of a partition.
