package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.nemesis.Sleeper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Takes probe rounds periodically on a virtual thread and records them as {@code PROBE} operations.
 */
final class ProbeLoop {

  static final String PROCESS = "prober";

  private final ClusterControl cluster;
  private final HistoryRecorder recorder;
  private final List<String> queues;
  private final Duration interval;
  private final Sleeper sleeper;
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final AtomicLong round = new AtomicLong();
  private Thread thread;

  ProbeLoop(
      ClusterControl cluster,
      HistoryRecorder recorder,
      List<String> queues,
      Duration interval,
      Sleeper sleeper) {
    this.cluster = cluster;
    this.recorder = recorder;
    this.queues = List.copyOf(queues);
    this.interval = interval;
    this.sleeper = sleeper;
  }

  void start() {
    thread =
        Thread.ofVirtual()
            .name("rmq-verify-prober")
            .start(
                () -> {
                  try {
                    while (!stopped.get()) {
                      probeOnce();
                      sleeper.sleep(interval);
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
  }

  /** Takes one round synchronously and returns the number of probes recorded. */
  int probeOnce() {
    List<Value.Probe> probes = cluster.probe(round.getAndIncrement(), queues);
    probes.forEach(p -> recorder.observe(PROCESS, OpFunction.PROBE, p));
    return probes.size();
  }

  /** Stops the periodic rounds and waits for the current one; idempotent. */
  void stop() throws InterruptedException {
    stopped.set(true);
    if (thread != null) {
      thread.join(interval.plusSeconds(30));
    }
  }
}
