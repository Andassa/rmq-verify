package io.github.andassa.rmqverify.model.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.model.testkit.ManualClock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;

class JsonlRoundTripProperties {

  private static final OpType[] OUTCOMES = {OpType.OK, OpType.FAIL, OpType.INFO};

  @Property(tries = 200)
  void writeThenReadIsIdentity(
      @ForAll @Size(max = 120) List<@IntRange(min = 0, max = 100_000) Integer> actions,
      @ForAll @StringLength(max = 40) String attribute,
      @ForAll boolean gzip)
      throws IOException {
    HistoryHeader header =
        HistoryHeader.of(
            "run-é\"\\",
            Map.of("q0", QueueKind.QUORUM, "q1", QueueKind.STREAM, "q2", QueueKind.CLASSIC),
            Map.of("free", attribute, "seed", "42"));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    OutputStream sinkStream = gzip ? new GZIPOutputStream(bytes) : bytes;
    History written;
    try (JsonlHistoryWriter writer = new JsonlHistoryWriter(sinkStream, header)) {
      HistoryRecorder recorder = new HistoryRecorder(header, new ManualClock(), writer);
      written = interpret(recorder, actions);
    }
    History read = new JsonlHistoryReader().read(new ByteArrayInputStream(bytes.toByteArray()));
    assertThat(read).isEqualTo(written);
  }

  /** Turns arbitrary integers into a valid history so that jqwik can shrink failures. */
  static History interpret(HistoryRecorder recorder, List<Integer> actions) {
    long[] nextSequence = new long[3];
    List<Message> published = new ArrayList<>();
    for (int action : actions) {
      int kind = action % 6;
      int arg = action / 6;
      switch (kind) {
        case 0 -> {
          int publisher = arg % 3;
          String queue = "q" + (arg / 3) % 3;
          Message m =
              new Message(
                  recorder.header().runId(),
                  "pub \"" + publisher + "\"",
                  queue,
                  nextSequence[publisher]++,
                  "uuid-" + published.size());
          published.add(m);
          recorder.invoke(m.publisherId(), OpFunction.PUBLISH, new Value.Publication(m));
          int outcome = (arg / 9) % 4;
          if (outcome < 3) {
            recorder.complete(
                m.publisherId(),
                OUTCOMES[outcome],
                OpFunction.CONFIRM,
                new Value.Publication(m),
                "e\n" + outcome);
          }
        }
        case 1 -> {
          if (!published.isEmpty()) {
            Message m = published.get(arg % published.size());
            Value.Delivery d = new Value.Delivery(m, arg % 2 == 0, arg);
            String consumer = "c" + arg % 2;
            recorder.observe(consumer, OpFunction.DELIVER, d);
            recorder.invoke(consumer + "-ack" + arg, OpFunction.ACK, d);
            recorder.complete(consumer + "-ack" + arg, OUTCOMES[arg % 3], OpFunction.ACK, d, "");
          }
        }
        case 2 ->
            recorder.observe(
                "c9",
                OpFunction.DELIVER,
                new Value.CorruptDelivery("q0", "sha-" + arg, arg % 2 == 1, arg));
        case 3 -> {
          Value.Fault fault =
              new Value.Fault(arg % 50, "kill-node", List.of("rabbit" + arg % 3), "d=" + arg);
          recorder.observe(
              "nemesis", arg % 2 == 0 ? OpFunction.NEMESIS_START : OpFunction.NEMESIS_STOP, fault);
        }
        case 4 ->
            recorder.observe(
                "prober",
                OpFunction.PROBE,
                new Value.Probe(
                    arg,
                    "rabbit1",
                    "q0",
                    arg % 2 == 0 ? "" : "rabbit2",
                    List.of("rabbit1", "rabbit2"),
                    List.of()));
        default -> {
          String process = "drain-" + arg;
          recorder.invoke(process, OpFunction.DRAIN, new Value.Drain("q1", 0));
          recorder.complete(
              process, OUTCOMES[arg % 3], OpFunction.DRAIN, new Value.Drain("q1", arg), "");
        }
      }
    }
    return recorder.snapshot();
  }
}
