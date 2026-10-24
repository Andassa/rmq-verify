package io.github.andassa.rmqverify.model;

import static io.github.andassa.rmqverify.model.testkit.HistoryFixture.msg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.model.testkit.ManualClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class HistoryRecorderTest {

  private static final HistoryHeader HEADER =
      HistoryHeader.of("run-1", Map.of("q", QueueKind.QUORUM), Map.of());

  @Test
  void timestampsNeverGoBackwards() {
    ManualClock clock = new ManualClock();
    HistoryRecorder recorder = new HistoryRecorder(HEADER, clock, op -> {});
    clock.set(100);
    Operation first =
        recorder.invoke("p1", OpFunction.PUBLISH, new Value.Publication(msg("p1", "q", 0)));
    clock.set(50);
    Operation second =
        recorder.complete(
            "p1", OpType.OK, OpFunction.CONFIRM, new Value.Publication(msg("p1", "q", 0)), "");
    clock.set(150);
    Operation third =
        recorder.observe("c1", OpFunction.DELIVER, new Value.Delivery(msg("p1", "q", 0), false, 1));
    assertThat(first.timestampNanos()).isEqualTo(100);
    assertThat(second.timestampNanos()).isEqualTo(100);
    assertThat(third.timestampNanos()).isEqualTo(150);
    assertThat(List.of(first.id(), second.id(), third.id())).containsExactly(0L, 1L, 2L);
    assertThat(third.type()).isEqualTo(OpType.OK);
    assertThat(recorder.size()).isEqualTo(3);
    assertThat(recorder.snapshot().size()).isEqualTo(3);
  }

  @Test
  void completeRejectsInvoke() {
    HistoryRecorder recorder = new HistoryRecorder(HEADER, new ManualClock(), op -> {});
    assertThatThrownBy(
            () ->
                recorder.complete("p1", OpType.INVOKE, OpFunction.ACK, new Value.Drain("q", 0), ""))
        .hasMessageContaining("cannot be of type INVOKE");
  }

  @Test
  void sinkFailureStopsTheRunWithoutRecording() {
    HistoryRecorder recorder =
        new HistoryRecorder(
            HEADER,
            new ManualClock(),
            op -> {
              throw new IOException("disk full");
            });
    assertThatThrownBy(() -> recorder.invoke("p", OpFunction.DRAIN, new Value.Drain("q", 0)))
        .isInstanceOf(HistoryWriteException.class)
        .hasMessageContaining("cannot persist operation 0")
        .hasRootCauseMessage("disk full");
    assertThat(recorder.size()).isZero();
  }

  @Test
  void identifiersStayDenseAfterARejectedOperation() {
    List<Operation> sunk = new ArrayList<>();
    HistoryRecorder recorder = new HistoryRecorder(HEADER, new ManualClock(), sunk::add);
    assertThatThrownBy(() -> recorder.observe("p", OpFunction.DELIVER, new Value.Drain("q", 0)))
        .isInstanceOf(IllegalArgumentException.class);
    Operation next = recorder.invoke("p", OpFunction.DRAIN, new Value.Drain("q", 0));
    assertThat(next.id()).isZero();
    assertThat(sunk).containsExactly(next);
  }

  @Test
  void longErrorsAreTruncated() {
    HistoryRecorder recorder = new HistoryRecorder(HEADER, new ManualClock(), op -> {});
    recorder.invoke("p", OpFunction.DRAIN, new Value.Drain("q", 0));
    Operation info =
        recorder.complete(
            "p", OpType.INFO, OpFunction.DRAIN, new Value.Drain("q", 0), "e".repeat(5000));
    assertThat(info.error()).hasSize(4096);
    recorder.invoke("p2", OpFunction.DRAIN, new Value.Drain("q", 0));
    Operation exact =
        recorder.complete(
            "p2", OpType.INFO, OpFunction.DRAIN, new Value.Drain("q", 0), "e".repeat(4096));
    assertThat(exact.error()).hasSize(4096);
  }

  @Test
  void concurrentRecordingYieldsAValidTotalOrder() throws Exception {
    List<Operation> sunk = new ArrayList<>();
    HistoryRecorder recorder = new HistoryRecorder(HEADER, NanoClock.system(), sunk::add);
    int publishers = 32;
    int perPublisher = 200;
    List<Future<Void>> futures = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int p = 0; p < publishers; p++) {
        String process = "p" + p;
        futures.add(
            executor.submit(
                () -> {
                  for (int s = 0; s < perPublisher; s++) {
                    Value.Publication value = new Value.Publication(msg(process, "q", s));
                    recorder.invoke(process, OpFunction.PUBLISH, value);
                    recorder.complete(process, OpType.OK, OpFunction.CONFIRM, value, "");
                  }
                  return null;
                }));
      }
    }
    for (Future<Void> future : futures) {
      future.get();
    }
    History history = recorder.snapshot();
    assertThat(history.size()).isEqualTo(publishers * perPublisher * 2);
    assertThat(sunk).isEqualTo(history.operations());
    assertThat(NanoClock.system().wallClock()).isNotNull();
  }
}
