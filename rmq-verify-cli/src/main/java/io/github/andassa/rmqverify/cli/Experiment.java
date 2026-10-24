package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.checker.Checkers;
import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.NanoClock;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.jsonl.JsonlHistoryWriter;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.FaultPlan;
import io.github.andassa.rmqverify.nemesis.FaultPlanCodec;
import io.github.andassa.rmqverify.nemesis.NemesisRunner;
import io.github.andassa.rmqverify.nemesis.Sleeper;
import io.github.andassa.rmqverify.report.FaultSummary;
import io.github.andassa.rmqverify.report.RunSummary;
import io.github.andassa.rmqverify.workload.Workload;
import io.github.andassa.rmqverify.workload.WorkloadConfig;
import io.github.andassa.rmqverify.workload.WorkloadException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * One run: workload and faults in parallel, heal, settle, drain, check. Artefacts are written to
 * the output directory as they become available, so a failed run still leaves its history.
 */
public final class Experiment {

  private static final Duration HEALTHY_TIMEOUT = Duration.ofMinutes(4);

  private final ClusterControl cluster;
  private final WorkloadFactory workloads;
  private final Function<HistoryRecorder, NemesisRunner> nemeses;
  private final FaultPlanCodec planCodec;
  private final Checkers checkers;
  private final NanoClock clock;
  private final Sleeper sleeper;
  private final PrintWriter out;

  /**
   * Creates an experiment.
   *
   * @param cluster cluster control
   * @param workloads workload factory
   * @param nemeses creates the nemesis runner bound to the run's recorder
   * @param planCodec writes {@code plan.json}
   * @param checkers checkers applied to the history
   * @param clock time source
   * @param sleeper waits
   * @param out progress output
   */
  public Experiment(
      ClusterControl cluster,
      WorkloadFactory workloads,
      Function<HistoryRecorder, NemesisRunner> nemeses,
      FaultPlanCodec planCodec,
      Checkers checkers,
      NanoClock clock,
      Sleeper sleeper,
      PrintWriter out) {
    this.cluster = cluster;
    this.workloads = workloads;
    this.nemeses = nemeses;
    this.planCodec = planCodec;
    this.checkers = checkers;
    this.clock = clock;
    this.sleeper = sleeper;
    this.out = out;
  }

  /**
   * Executes a run.
   *
   * @param settings configuration
   * @param plan faults to inject
   * @return the summary and the history
   * @throws ClusterException if the cluster is unsafe, unhealthy or cannot be healed
   * @throws WorkloadException if the workload cannot start
   * @throws IOException if artefacts cannot be written
   * @throws InterruptedException if interrupted; faults are healed first
   */
  public RunOutcome run(ExperimentSettings settings, FaultPlan plan)
      throws ClusterException, WorkloadException, IOException, InterruptedException {
    Path dir = Files.createDirectories(settings.outputDir());
    planCodec.write(plan, dir.resolve("plan.json"));
    cluster.verifySafety();
    cluster.healAll();
    cluster.waitHealthy(HEALTHY_TIMEOUT);
    String version = cluster.brokerVersion();
    WorkloadConfig config = settings.workload();
    HistoryHeader header = header(settings, config, version);
    String startedAt = java.time.Instant.now().toString();
    progress(
        "run %s: RabbitMQ %s, %s x%d, %d fault(s), seed %d",
        settings.runId(),
        version,
        settings.kind(),
        settings.queues(),
        plan.events().size(),
        settings.seed());
    History history;
    try (JsonlHistoryWriter writer =
        JsonlHistoryWriter.open(dir.resolve("history.jsonl"), header)) {
      HistoryRecorder recorder = new HistoryRecorder(header, clock, writer);
      runPhases(settings, plan, config, recorder);
      history = recorder.snapshot();
    }
    List<Verdict> verdicts = checkers.checkAll(history, settings.checks());
    RunSummary summary =
        new RunSummary(
            settings.runId(),
            settings.scenario(),
            settings.repetition(),
            version,
            settings.kind().name(),
            settings.seed(),
            startedAt,
            settings.duration().toMillis(),
            settings.describe(),
            faults(plan),
            verdicts);
    progress(
        "run %s: %d operations, overall %s", settings.runId(), history.size(), summary.overall());
    return new RunOutcome(dir, summary, history);
  }

  private void runPhases(
      ExperimentSettings settings, FaultPlan plan, WorkloadConfig config, HistoryRecorder recorder)
      throws WorkloadException, ClusterException, InterruptedException {
    try (Workload workload = workloads.create(config, recorder)) {
      ProbeLoop probes =
          new ProbeLoop(
              cluster, recorder, workload.queueNames(), settings.probeInterval(), sleeper);
      try {
        workload.prepare();
        workload.startConsumers();
        long start = clock.nanoTime();
        workload.startPublishers();
        probes.start();
        nemeses.apply(recorder).execute(plan, start);
        sleeper.sleep(Duration.ofNanos(start + settings.duration().toNanos() - clock.nanoTime()));
        progress("load phase over, stopping publishers");
        workload.stopPublishers();
        cluster.healAll();
        cluster.waitHealthy(HEALTHY_TIMEOUT);
        progress("cluster healthy, settling for %d s", settings.settle().toSeconds());
        sleeper.sleep(settings.settle());
        probes.stop();
        probes.probeOnce();
        workload.stopConsumers();
        boolean drained = workload.drain();
        progress("drain %s", drained ? "complete" : "INCOMPLETE (verdicts may be UNKNOWN)");
      } finally {
        probes.stop();
      }
    }
  }

  private static HistoryHeader header(
      ExperimentSettings settings, WorkloadConfig config, String version) {
    Map<String, QueueKind> queues = new TreeMap<>();
    config.queues().forEach(q -> queues.put(q.name(), q.kind()));
    Map<String, String> attributes = new TreeMap<>(settings.describe());
    attributes.put("rabbitmqVersion", version);
    attributes.put("seed", Long.toString(settings.seed()));
    attributes.put("scenario", settings.scenario());
    attributes.put("repetition", Integer.toString(settings.repetition()));
    return HistoryHeader.of(settings.runId(), queues, attributes);
  }

  static List<FaultSummary> faults(FaultPlan plan) {
    return plan.events().stream()
        .map(
            (FaultEvent e) ->
                new FaultSummary(
                    e.index(),
                    e.nemesis(),
                    e.startMillis(),
                    e.durationMillis(),
                    e.targets(),
                    e.describe()))
        .toList();
  }

  private void progress(String format, Object... arguments) {
    out.printf(java.util.Locale.ROOT, format + "%n", arguments);
    out.flush();
  }

  /**
   * Result of a run.
   *
   * @param directory artefact directory
   * @param summary configuration, faults and verdicts
   * @param history the recorded history
   */
  public record RunOutcome(Path directory, RunSummary summary, History history) {}
}
