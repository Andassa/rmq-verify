package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.Value;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-message index of a history: publication, confirm, deliveries and acks, plus the drain outcome
 * of each queue and the fault windows. Built in one linear pass.
 */
public final class MessageLedger {

  private final History history;
  private final Map<String, MessageRecord> records = new LinkedHashMap<>();
  private final List<Operation> deliveries = new ArrayList<>();
  private final List<Operation> unknownDeliveries = new ArrayList<>();
  private final List<Operation> corruptDeliveries = new ArrayList<>();
  private final Map<String, Operation> drainCompletions = new HashMap<>();
  private final List<FaultWindow> faults = new ArrayList<>();

  private MessageLedger(History history) {
    this.history = history;
    Map<Integer, Integer> openFaults = new HashMap<>();
    for (Operation op : history.operations()) {
      index(op, openFaults);
    }
  }

  /**
   * Indexes a history.
   *
   * @param history the history
   * @return the ledger
   */
  public static MessageLedger of(History history) {
    return new MessageLedger(history);
  }

  private void index(Operation op, Map<Integer, Integer> openFaults) {
    switch (op.function()) {
      case PUBLISH -> {
        Value.Publication p = (Value.Publication) op.value();
        records.put(p.msg().uuid(), new MessageRecord(p.msg(), op));
      }
      case CONFIRM -> records.get(((Value.Publication) op.value()).msg().uuid()).confirmedBy(op);
      case DELIVER -> indexDelivery(op);
      case ACK -> indexAck(op);
      case DRAIN -> {
        if (op.isCompletion()) {
          Value.Drain drain = (Value.Drain) op.value();
          drainCompletions.merge(drain.queue(), op, MessageLedger::preferOk);
        }
      }
      case NEMESIS_START -> {
        Value.Fault fault = (Value.Fault) op.value();
        openFaults.put(fault.index(), faults.size());
        faults.add(new FaultWindow(fault.index(), fault.nemesis(), op, Optional.empty()));
      }
      case NEMESIS_STOP -> {
        Integer position = openFaults.remove(((Value.Fault) op.value()).index());
        if (position != null) {
          FaultWindow open = faults.get(position);
          faults.set(
              position,
              new FaultWindow(open.index(), open.nemesis(), open.start(), Optional.of(op)));
        }
      }
      default -> {
        // PROBE: judged by SplitBrainIndicatorsChecker directly from the history.
      }
    }
  }

  private void indexDelivery(Operation op) {
    deliveries.add(op);
    if (op.value() instanceof Value.Delivery d) {
      MessageRecord record = records.get(d.msg().uuid());
      if (record == null) {
        unknownDeliveries.add(op);
      } else {
        record.deliveredBy(op);
      }
    } else {
      corruptDeliveries.add(op);
    }
  }

  private void indexAck(Operation op) {
    if (op.isCompletion() && op.value() instanceof Value.Delivery d) {
      MessageRecord record = records.get(d.msg().uuid());
      if (record != null) {
        record.acknowledgedBy(op);
      }
    }
  }

  private static Operation preferOk(Operation current, Operation candidate) {
    return current.type() == OpType.OK ? current : candidate;
  }

  /**
   * Returns the indexed history.
   *
   * @return the history
   */
  public History history() {
    return history;
  }

  /**
   * Returns the published messages in publication order.
   *
   * @return an unmodifiable view
   */
  public Collection<MessageRecord> messages() {
    return Collections.unmodifiableCollection(records.values());
  }

  /**
   * Looks a published message up.
   *
   * @param uuid message identifier
   * @return its record, empty if it was never published in this run
   */
  public Optional<MessageRecord> record(String uuid) {
    return Optional.ofNullable(records.get(uuid));
  }

  /**
   * Returns every {@code DELIVER} operation, in history order.
   *
   * @return an unmodifiable view
   */
  public List<Operation> deliveries() {
    return Collections.unmodifiableList(deliveries);
  }

  /**
   * Returns decoded deliveries of messages that were never published in this history.
   *
   * @return an unmodifiable view
   */
  public List<Operation> unknownDeliveries() {
    return Collections.unmodifiableList(unknownDeliveries);
  }

  /**
   * Returns deliveries whose payload could not be decoded.
   *
   * @return an unmodifiable view
   */
  public List<Operation> corruptDeliveries() {
    return Collections.unmodifiableList(corruptDeliveries);
  }

  /**
   * Tells whether the drain of a queue met its end criterion.
   *
   * @param queue queue name
   * @return {@code true} if a {@code DRAIN} completed with {@code OK}
   */
  public boolean drained(String queue) {
    Operation completion = drainCompletions.get(queue);
    return completion != null && completion.type() == OpType.OK;
  }

  /**
   * Returns the completion of the drain of a queue, preferring a successful one.
   *
   * @param queue queue name
   * @return the completion, empty if the queue was never drained to completion
   */
  public Optional<Operation> drainCompletion(String queue) {
    return Optional.ofNullable(drainCompletions.get(queue));
  }

  /**
   * Returns the type of a queue as declared in the header.
   *
   * @param queue queue name
   * @return the kind, empty for queues the run did not declare
   */
  public Optional<QueueKind> kind(String queue) {
    return Optional.ofNullable(history.header().queues().get(queue));
  }

  /**
   * Tells whether a queue is a log (stream) according to the header.
   *
   * @param queue queue name
   * @return {@code true} for declared streams
   */
  public boolean isLog(String queue) {
    return kind(queue).map(QueueKind::isLog).orElse(false);
  }

  /**
   * Returns the fault windows in start order.
   *
   * @return an unmodifiable view
   */
  public List<FaultWindow> faults() {
    return Collections.unmodifiableList(faults);
  }

  /**
   * Returns the timestamp of the last operation.
   *
   * @return monotonic nanoseconds, {@code 0} for an empty history
   */
  public long endNanos() {
    List<Operation> ops = history.operations();
    return ops.isEmpty() ? 0 : ops.get(ops.size() - 1).timestampNanos();
  }

  /**
   * Tells whether the history contains at least one publication.
   *
   * @return {@code true} if something was published
   */
  public boolean hasPublications() {
    return !records.isEmpty();
  }
}
