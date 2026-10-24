package io.github.andassa.rmqverify.nemesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.testkit.ManualClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class NemesisRunnerTest {

  private final ManualClock clock = new ManualClock();
  private final RecordingCluster cluster = new RecordingCluster();
  private final HistoryRecorder recorder =
      new HistoryRecorder(HistoryHeader.of("r", Map.of(), Map.of()), clock, op -> {});
  private final List<Thread> hooks = new ArrayList<>();
  private final List<Thread> removed = new ArrayList<>();
  private final ShutdownHooks fakeHooks =
      new ShutdownHooks() {
        @Override
        public void register(Thread hook) {
          hooks.add(hook);
        }

        @Override
        public void unregister(Thread hook) {
          removed.add(hook);
        }
      };

  private NemesisRunner runner(Sleeper sleeper) {
    return new NemesisRunner(NemesisRegistry.load(), cluster, recorder, clock, sleeper, fakeHooks);
  }

  private static FaultPlan plan(FaultEvent... events) {
    return new FaultPlan(1, 1, 120_000, List.of("rabbit1", "rabbit2", "rabbit3"), List.of(events));
  }

  private static FaultEvent event(int index, String nemesis, long start, String target) {
    return new FaultEvent(index, nemesis, start, 1000, List.of(target), new TreeMap<>());
  }

  @Test
  void injectsHealsAndRecordsEachWindowOnSchedule() throws Exception {
    List<Long> wakeUps = new ArrayList<>();
    runner(
            d -> {
              clock.advance(d);
              wakeUps.add(clock.nanoTime() / 1_000_000);
            })
        .execute(
            plan(event(0, "kill-node", 5000, "rabbit2"), event(1, "pause-node", 8000, "rabbit3")),
            0);
    assertThat(cluster.calls)
        .containsExactly(
            "verifySafety",
            "kill rabbit2",
            "start rabbit2",
            "pause rabbit3",
            "unpause rabbit3",
            "healAll");
    assertThat(wakeUps).containsExactly(5000L, 6000L, 8000L, 9000L);
    List<Operation> ops = recorder.snapshot().operations();
    assertThat(ops)
        .extracting(Operation::function)
        .containsExactly(
            OpFunction.NEMESIS_START,
            OpFunction.NEMESIS_STOP,
            OpFunction.NEMESIS_START,
            OpFunction.NEMESIS_STOP);
    assertThat(ops).extracting(Operation::type).containsOnly(OpType.OK);
    assertThat(hooks).hasSize(1);
    assertThat(removed).isEqualTo(hooks);
  }

  @Test
  void anUnsafeClusterIsNeverTouched() {
    cluster.unsafe = true;
    assertThatThrownBy(() -> runner(d -> {}).execute(plan(event(0, "kill-node", 0, "rabbit1")), 0))
        .hasMessageContaining("not the test cluster");
    assertThat(cluster.calls).containsExactly("verifySafety");
    assertThat(recorder.size()).isZero();
  }

  @Test
  void failedInjectionsAndHealsAreIndeterminateAndStillHealed() throws Exception {
    cluster.failing = Set.of("kill", "start");
    runner(d -> {}).execute(plan(event(0, "kill-node", 0, "rabbit1")), 0);
    List<Operation> ops = recorder.snapshot().operations();
    assertThat(ops).extracting(Operation::type).containsExactly(OpType.INFO, OpType.INFO);
    assertThat(ops.get(0).error()).contains("kill rabbit1 failed");
    assertThat(cluster.calls).endsWith("healAll");
  }

  @Test
  void interruptionHealsBeforeLeaving() {
    Sleeper interrupted =
        d -> {
          if (cluster.calls.contains("kill rabbit1")) {
            throw new InterruptedException("stop");
          }
        };
    assertThatThrownBy(
            () -> runner(interrupted).execute(plan(event(0, "kill-node", 0, "rabbit1")), 0))
        .isInstanceOf(InterruptedException.class);
    assertThat(cluster.calls)
        .containsExactly("verifySafety", "kill rabbit1", "start rabbit1", "healAll");
    assertThat(removed).hasSize(1);
  }

  @Test
  void theShutdownHookHealsEverything() throws Exception {
    runner(d -> {}).execute(plan(), 0);
    cluster.calls.clear();
    runHook(hooks.get(0));
    assertThat(cluster.calls).containsExactly("healAll");
    cluster.failing = Set.of("healAll");
    runHook(hooks.get(0));
    assertThat(cluster.calls).containsExactly("healAll", "healAll");
  }

  private static void runHook(Thread hook) throws InterruptedException {
    Thread copy = new Thread(hook);
    copy.start();
    copy.join();
  }

  @Test
  void runtimeHooksRegisterAndUnregister() {
    ShutdownHooks runtime = ShutdownHooks.runtime();
    Thread hook = new Thread(() -> {});
    runtime.register(hook);
    runtime.unregister(hook);
    runtime.unregister(hook);
    assertThat(hook.isAlive()).isFalse();
  }

  @Test
  void realSleeperIgnoresNonPositiveDurations() throws Exception {
    Sleeper.real().sleep(java.time.Duration.ofMillis(-5));
    Sleeper.real().sleep(java.time.Duration.ofMillis(1));
    assertThat(clock.nanoTime()).isZero();
  }
}
