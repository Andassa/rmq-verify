package io.github.andassa.rmqverify.jmh;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.NanoClock;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.Value;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Builds large, realistic histories in memory: publishers with confirms, consumers with acks,
 * occasional indeterminate outcomes and redeliveries, fault windows, probe rounds and a drain.
 */
public final class LargeHistories {

  private LargeHistories() {}

  /**
   * Generates a history of at least {@code operations} operations.
   *
   * @param operations target size
   * @param seed seed of the random choices
   * @return the validated history
   */
  public static History generate(int operations, long seed) {
    long[] now = {0};
    NanoClock clock =
        new NanoClock() {
          @Override
          public long nanoTime() {
            now[0] += 50_000;
            return now[0];
          }

          @Override
          public Instant wallClock() {
            return Instant.EPOCH.plusNanos(now[0]);
          }
        };
    HistoryRecorder recorder =
        new HistoryRecorder(
            HistoryHeader.of(
                "bench",
                Map.of("rmqv.q0", QueueKind.QUORUM, "rmqv.q1", QueueKind.QUORUM),
                Map.of()),
            clock,
            op -> {});
    SplittableRandom random = new SplittableRandom(seed);
    long[] sequences = new long[16];
    long tag = 1;
    int fault = 0;
    while (recorder.size() < operations) {
      int publisher = random.nextInt(sequences.length);
      String queue = "rmqv.q" + (publisher % 2);
      Message m =
          new Message(
              "bench", "p" + publisher, queue, sequences[publisher]++, "u" + recorder.size());
      Value.Publication publication = new Value.Publication(m);
      recorder.invoke(m.publisherId(), OpFunction.PUBLISH, publication);
      boolean indeterminate = random.nextInt(100) == 0;
      recorder.complete(
          m.publisherId(),
          indeterminate ? OpType.INFO : OpType.OK,
          OpFunction.CONFIRM,
          publication,
          "");
      String consumer = "c" + (publisher % 4);
      Value.Delivery delivery = new Value.Delivery(m, false, tag++);
      recorder.observe(consumer, OpFunction.DELIVER, delivery);
      recorder.invoke(consumer, OpFunction.ACK, delivery);
      recorder.complete(consumer, OpType.OK, OpFunction.ACK, delivery, "");
      if (random.nextInt(2000) == 0) {
        Value.Fault f = new Value.Fault(fault, "kill-node", List.of("rabbit1"), "");
        recorder.observe("nemesis", OpFunction.NEMESIS_START, f);
        recorder.observe(
            "prober",
            OpFunction.PROBE,
            new Value.Probe(
                fault,
                "rabbit1",
                queue,
                "rabbit1",
                List.of("rabbit1", "rabbit2", "rabbit3"),
                List.of()));
        recorder.observe("nemesis", OpFunction.NEMESIS_STOP, f);
        fault++;
      }
    }
    for (String queue : List.of("rmqv.q0", "rmqv.q1")) {
      recorder.invoke("drain:" + queue, OpFunction.DRAIN, new Value.Drain(queue, 0));
      recorder.complete(
          "drain:" + queue, OpType.OK, OpFunction.DRAIN, new Value.Drain(queue, 0), "");
    }
    return recorder.snapshot();
  }
}
