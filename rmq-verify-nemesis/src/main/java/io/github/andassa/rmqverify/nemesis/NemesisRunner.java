package io.github.andassa.rmqverify.nemesis;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.cluster.Logs;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.NanoClock;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Value;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a fault plan against the cluster and records each fault window in the history. Whatever
 * happens (exception, interruption, JVM shutdown) every fault is healed: per event, then globally
 * in a {@code finally} block and from a shutdown hook.
 */
public final class NemesisRunner {

  /** Process name of the nemesis in histories. */
  public static final String PROCESS = "nemesis";

  private static final Logger LOG = LoggerFactory.getLogger(NemesisRunner.class);

  private final NemesisRegistry registry;
  private final ClusterControl cluster;
  private final HistoryRecorder recorder;
  private final NanoClock clock;
  private final Sleeper sleeper;
  private final ShutdownHooks hooks;

  /**
   * Creates a runner.
   *
   * @param registry nemeses
   * @param cluster cluster control
   * @param recorder history recorder
   * @param clock time source
   * @param sleeper waits until each fault starts and ends
   * @param hooks registration of the JVM shutdown hook
   */
  public NemesisRunner(
      NemesisRegistry registry,
      ClusterControl cluster,
      HistoryRecorder recorder,
      NanoClock clock,
      Sleeper sleeper,
      ShutdownHooks hooks) {
    this.registry = registry;
    this.cluster = cluster;
    this.recorder = recorder;
    this.clock = clock;
    this.sleeper = sleeper;
    this.hooks = hooks;
  }

  /**
   * Runs a plan. The safety guard is checked before the first injection; if it fails nothing is
   * injected.
   *
   * @param plan the faults
   * @param runStartNanos monotonic time the plan offsets are relative to
   * @throws ClusterException if the cluster cannot be proven to be the test cluster
   * @throws InterruptedException if interrupted; faults are healed before returning
   */
  public void execute(FaultPlan plan, long runStartNanos)
      throws ClusterException, InterruptedException {
    cluster.verifySafety();
    Thread hook = new Thread(this::healAllQuietly, "rmq-verify-heal");
    hooks.register(hook);
    try {
      for (FaultEvent event : plan.events()) {
        Nemesis nemesis =
            registry
                .byName(event.nemesis())
                .orElseThrow(
                    () -> new IllegalArgumentException("unknown nemesis " + event.nemesis()));
        waitUntil(runStartNanos, event.startMillis());
        Value.Fault fault =
            new Value.Fault(event.index(), event.nemesis(), event.targets(), event.describe());
        try {
          nemesis.inject(event, cluster);
          recorder.record(PROCESS, OpType.OK, OpFunction.NEMESIS_START, fault, "");
        } catch (ClusterException e) {
          LOG.warn(
              "fault {} ({}) only partially injected: {}",
              event.index(),
              event.nemesis(),
              Logs.safe(e.getMessage()));
          recorder.record(PROCESS, OpType.INFO, OpFunction.NEMESIS_START, fault, e.getMessage());
        }
        try {
          waitUntil(runStartNanos, event.endMillis());
        } finally {
          heal(nemesis, event, fault);
        }
      }
    } finally {
      healAllQuietly();
      hooks.unregister(hook);
    }
  }

  private void heal(Nemesis nemesis, FaultEvent event, Value.Fault fault) {
    try {
      nemesis.heal(event, cluster);
      recorder.record(PROCESS, OpType.OK, OpFunction.NEMESIS_STOP, fault, "");
    } catch (ClusterException e) {
      LOG.error(
          "fault {} ({}) could not be healed: {}",
          event.index(),
          event.nemesis(),
          Logs.safe(e.getMessage()));
      recorder.record(PROCESS, OpType.INFO, OpFunction.NEMESIS_STOP, fault, e.getMessage());
    }
  }

  private void healAllQuietly() {
    try {
      cluster.healAll();
    } catch (ClusterException e) {
      LOG.error("global heal incomplete: {}", Logs.safe(e.getMessage()));
    }
  }

  private void waitUntil(long runStartNanos, long offsetMillis) throws InterruptedException {
    long target = runStartNanos + Duration.ofMillis(offsetMillis).toNanos();
    sleeper.sleep(Duration.ofNanos(target - clock.nanoTime()));
  }
}
