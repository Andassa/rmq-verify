package io.github.andassa.rmqverify.model.testkit;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.Value;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder of synthetic histories for tests. Every call advances the clock by one millisecond
 * so that operations have distinct, increasing timestamps.
 */
public final class HistoryFixture {

  public static final String RUN = "run-1";

  private final ManualClock clock = new ManualClock();
  private final List<Operation> sunk = new ArrayList<>();
  private final HistoryRecorder recorder;

  public HistoryFixture(Map<String, QueueKind> queues) {
    this.recorder = new HistoryRecorder(HistoryHeader.of(RUN, queues, Map.of()), clock, sunk::add);
  }

  public static HistoryFixture quorum(String... queues) {
    return withKind(QueueKind.QUORUM, queues);
  }

  public static HistoryFixture stream(String... queues) {
    return withKind(QueueKind.STREAM, queues);
  }

  public static HistoryFixture classic(String... queues) {
    return withKind(QueueKind.CLASSIC, queues);
  }

  private static HistoryFixture withKind(QueueKind kind, String... queues) {
    java.util.TreeMap<String, QueueKind> map = new java.util.TreeMap<>();
    for (String queue : queues) {
      map.put(queue, kind);
    }
    return new HistoryFixture(map);
  }

  public static Message msg(String publisher, String queue, long sequence) {
    return new Message(RUN, publisher, queue, sequence, publisher + "/" + queue + "/" + sequence);
  }

  public HistoryFixture advance(Duration duration) {
    clock.advance(duration);
    return this;
  }

  public Operation publish(Message m) {
    tick();
    return recorder.invoke(m.publisherId(), OpFunction.PUBLISH, new Value.Publication(m));
  }

  public Operation confirm(Message m, OpType type) {
    tick();
    String error = type == OpType.OK ? "" : "simulated " + type;
    return recorder.complete(
        m.publisherId(), type, OpFunction.CONFIRM, new Value.Publication(m), error);
  }

  /** Publishes and completes in one call. */
  public Operation published(Message m, OpType type) {
    publish(m);
    return confirm(m, type);
  }

  public Operation deliver(String consumer, Message m, boolean redelivered, long tag) {
    tick();
    return recorder.observe(consumer, OpFunction.DELIVER, new Value.Delivery(m, redelivered, tag));
  }

  public Operation deliverCorrupt(String consumer, String queue, String digest, long tag) {
    tick();
    return recorder.observe(
        consumer, OpFunction.DELIVER, new Value.CorruptDelivery(queue, digest, false, tag));
  }

  public Operation ack(String consumer, Message m, boolean redelivered, long tag, OpType type) {
    Value.Delivery delivery = new Value.Delivery(m, redelivered, tag);
    tick();
    recorder.invoke(consumer, OpFunction.ACK, delivery);
    tick();
    return recorder.complete(consumer, type, OpFunction.ACK, delivery, "");
  }

  /** Delivers then acknowledges successfully. */
  public Operation consumed(String consumer, Message m, boolean redelivered, long tag) {
    deliver(consumer, m, redelivered, tag);
    return ack(consumer, m, redelivered, tag, OpType.OK);
  }

  public Operation drainStart(String queue) {
    tick();
    return recorder.invoke(drainProcess(queue), OpFunction.DRAIN, new Value.Drain(queue, 0));
  }

  public Operation drainEnd(String queue, OpType type, long delivered) {
    tick();
    return recorder.complete(
        drainProcess(queue), type, OpFunction.DRAIN, new Value.Drain(queue, delivered), "");
  }

  public static String drainProcess(String queue) {
    return "drain:" + queue;
  }

  public Operation faultStart(int index, String nemesis, String... targets) {
    tick();
    return recorder.observe(
        "nemesis", OpFunction.NEMESIS_START, new Value.Fault(index, nemesis, List.of(targets), ""));
  }

  public Operation faultStop(int index, String nemesis, String... targets) {
    tick();
    return recorder.observe(
        "nemesis", OpFunction.NEMESIS_STOP, new Value.Fault(index, nemesis, List.of(targets), ""));
  }

  public Operation probe(
      long round,
      String observer,
      String subject,
      String leader,
      List<String> running,
      List<String> partitions) {
    tick();
    return recorder.observe(
        "prober",
        OpFunction.PROBE,
        new Value.Probe(round, observer, subject, leader, running, partitions));
  }

  public Operation record(String process, OpType type, OpFunction function, Value value) {
    tick();
    return recorder.record(process, type, function, value, "");
  }

  public HistoryRecorder recorder() {
    return recorder;
  }

  public List<Operation> sunk() {
    return sunk;
  }

  public History build() {
    return recorder.snapshot();
  }

  private void tick() {
    clock.advance(Duration.ofMillis(1));
  }
}
