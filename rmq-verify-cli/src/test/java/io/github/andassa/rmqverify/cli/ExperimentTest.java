package io.github.andassa.rmqverify.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.checker.CheckerContext;
import io.github.andassa.rmqverify.checker.Checkers;
import io.github.andassa.rmqverify.model.NanoClock;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.VerdictStatus;
import io.github.andassa.rmqverify.model.jsonl.JsonlHistoryReader;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.FaultPlan;
import io.github.andassa.rmqverify.nemesis.FaultPlanCodec;
import io.github.andassa.rmqverify.nemesis.NemesisRegistry;
import io.github.andassa.rmqverify.nemesis.NemesisRunner;
import io.github.andassa.rmqverify.nemesis.ShutdownHooks;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentTest {

  private final FakeCluster cluster = new FakeCluster();
  private final StringWriter output = new StringWriter();
  private SyntheticWorkload workload;

  static ExperimentSettings settings(Path dir) {
    return new ExperimentSettings(
        dir,
        "unit",
        1,
        "r-unit-1",
        9,
        QueueKind.QUORUM,
        1,
        0,
        1,
        1,
        10,
        Duration.ofSeconds(5),
        200,
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        Duration.ZERO,
        Duration.ofHours(1),
        CheckerContext.defaults());
  }

  private Experiment experiment(boolean loseOne) {
    NemesisRegistry registry = NemesisRegistry.load();
    ShutdownHooks noHooks =
        new ShutdownHooks() {
          @Override
          public void register(Thread hook) {}

          @Override
          public void unregister(Thread hook) {}
        };
    return new Experiment(
        cluster,
        (config, recorder) -> workload = new SyntheticWorkload(config, recorder, loseOne),
        recorder ->
            new NemesisRunner(registry, cluster, recorder, NanoClock.system(), d -> {}, noHooks),
        new FaultPlanCodec(registry),
        Checkers.load(),
        NanoClock.system(),
        d -> {},
        new PrintWriter(output));
  }

  private static FaultPlan killPlan() {
    return new FaultPlan(
        1,
        9,
        5000,
        List.of("rabbit1", "rabbit2", "rabbit3"),
        List.of(new FaultEvent(0, "kill-node", 0, 10, List.of("rabbit2"), new TreeMap<>())));
  }

  @Test
  void aHealthyRunIsValidAndLeavesItsArtefacts(@TempDir Path dir) throws Exception {
    Experiment.RunOutcome outcome = experiment(false).run(settings(dir.resolve("run")), killPlan());
    assertThat(outcome.summary().overall()).isEqualTo(VerdictStatus.VALID);
    assertThat(outcome.summary().faults())
        .singleElement()
        .satisfies(f -> assertThat(f.nemesis()).isEqualTo("kill-node"));
    assertThat(outcome.summary().rabbitmqVersion()).isEqualTo("4.4.0");
    assertThat(workload.phases)
        .containsExactly(
            "prepare",
            "startConsumers",
            "startPublishers",
            "stopPublishers",
            "stopConsumers",
            "drain",
            "close");
    assertThat(cluster.calls)
        .containsSubsequence(
            "verifySafety",
            "healAll",
            "waitHealthy",
            "kill rabbit2",
            "start rabbit2",
            "healAll",
            "healAll",
            "waitHealthy");
    Path run = outcome.directory();
    assertThat(new JsonlHistoryReader().read(run.resolve("history.jsonl")))
        .isEqualTo(outcome.history());
    assertThat(Files.readString(run.resolve("plan.json"))).contains("\"kill-node\"");
    assertThat(outcome.history().header().attributes())
        .containsEntry("rabbitmqVersion", "4.4.0")
        .containsEntry("scenario", "unit")
        .containsEntry("seed", "9");
    assertThat(output.toString()).contains("overall VALID", "drain complete");
    RunArtifacts.write(run, outcome.summary(), outcome.history());
    assertThat(run.resolve("report.json")).exists();
  }

  @Test
  void aLostMessageMakesTheRunInvalid(@TempDir Path dir) throws Exception {
    Experiment.RunOutcome outcome =
        experiment(true).run(settings(dir), killPlan().withEvents(List.of()));
    assertThat(outcome.summary().overall()).isEqualTo(VerdictStatus.INVALID);
    assertThat(outcome.summary().verdicts())
        .filteredOn(v -> v.checker().equals("LostMessages"))
        .singleElement()
        .satisfies(v -> assertThat(v.violationCount()).isEqualTo(1));
  }

  @Test
  void anUnhealthyClusterStopsTheRunBeforeAnyLoad(@TempDir Path dir) {
    cluster.unhealthy = true;
    assertThatThrownBy(() -> experiment(false).run(settings(dir), killPlan()))
        .hasMessageContaining("not healthy");
    assertThat(workload).isNull();
  }

  @Test
  void settingsDescribeTheWorkload(@TempDir Path dir) {
    ExperimentSettings settings = settings(dir);
    assertThat(settings.queueName(0)).isEqualTo("rmqv.r-unit-1.quorum.0");
    assertThat(settings.workload().queues()).hasSize(1);
    assertThat(settings.workload().prefetch()).isEqualTo(10);
    assertThat(settings.describe())
        .containsEntry("publishers", "1")
        .containsEntry("durationSeconds", "5");
  }
}
