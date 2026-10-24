package io.github.andassa.rmqverify.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.checker.Checkers;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.model.NanoClock;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.FaultPlan;
import io.github.andassa.rmqverify.nemesis.FaultPlanCodec;
import io.github.andassa.rmqverify.nemesis.NemesisRegistry;
import io.github.andassa.rmqverify.nemesis.NemesisRunner;
import io.github.andassa.rmqverify.nemesis.ShutdownHooks;
import io.github.andassa.rmqverify.workload.WorkloadException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/** Commands that drive a cluster, run against the fake cluster and the synthetic workload. */
class CommandFlowsTest {

  private final StringWriter out = new StringWriter();

  private static ExperimentFactory fake(boolean loseOne) {
    NemesisRegistry registry = NemesisRegistry.load();
    FakeCluster cluster = new FakeCluster();
    ShutdownHooks none =
        new ShutdownHooks() {
          @Override
          public void register(Thread hook) {}

          @Override
          public void unregister(Thread hook) {}
        };
    return (env, writer) ->
        new Experiment(
            cluster,
            (c, r) -> new SyntheticWorkload(c, r, loseOne),
            r -> new NemesisRunner(registry, cluster, r, NanoClock.system(), d -> {}, none),
            new FaultPlanCodec(registry),
            Checkers.load(),
            NanoClock.system(),
            d -> {},
            writer);
  }

  private int execute(Object command, String... args) {
    CommandLine cli = Main.configure(new CommandLine(command));
    cli.setOut(new PrintWriter(out));
    cli.setErr(new PrintWriter(out));
    return cli.execute(args);
  }

  @Test
  void runWritesEveryArtefactAndReturnsTheVerdict(@TempDir Path dir) {
    RunCommand run = new RunCommand();
    run.experiments = fake(false);
    assertThat(
            execute(
                run,
                "--duration",
                "5",
                "--seed",
                "4",
                "--nemesis",
                "kill-node",
                "--out",
                dir.resolve("ok").toString()))
        .isEqualTo(ExitCodes.VALID);
    assertThat(dir.resolve("ok/report.html")).exists();
    assertThat(dir.resolve("ok/plan.json")).exists();
    RunCommand lossy = new RunCommand();
    lossy.experiments = fake(true);
    assertThat(execute(lossy, "--duration", "5", "--out", dir.resolve("lost").toString()))
        .isEqualTo(ExitCodes.INVALID);
    assertThat(out.toString()).contains("LostMessages", "INVALID");
  }

  @Test
  void replayUsesThePlanAndItsSeed(@TempDir Path dir) throws Exception {
    Path plan = dir.resolve("plan.json");
    new FaultPlanCodec(NemesisRegistry.load())
        .write(
            new FaultPlan(
                1,
                77,
                5000,
                List.of("rabbit1", "rabbit2", "rabbit3"),
                List.of(
                    new FaultEvent(0, "pause-node", 0, 10, List.of("rabbit3"), new TreeMap<>()))),
            plan);
    RunCommand replay = new RunCommand();
    replay.experiments = fake(false);
    assertThat(
            execute(
                replay,
                "--plan",
                plan.toString(),
                "--duration",
                "5",
                "--out",
                dir.resolve("r").toString()))
        .isZero();
    assertThat(Files.readString(dir.resolve("r/history.jsonl")))
        .contains("\"seed\":\"77\"", "pause-node");
  }

  @Test
  void minimizeReducesAFaultIndependentLossToAnEmptyPlan(@TempDir Path dir) throws Exception {
    Path plan = dir.resolve("plan.json");
    new FaultPlanCodec(NemesisRegistry.load())
        .write(
            new FaultPlan(
                1,
                5,
                60_000,
                List.of("rabbit1", "rabbit2", "rabbit3"),
                List.of(
                    new FaultEvent(0, "kill-node", 5_000, 10, List.of("rabbit1"), new TreeMap<>()),
                    new FaultEvent(
                        1, "pause-node", 20_000, 10, List.of("rabbit2"), new TreeMap<>()))),
            plan);
    MinimizeCommand minimize = new MinimizeCommand();
    minimize.experiments = fake(true);
    assertThat(
            execute(
                minimize,
                "--plan",
                plan.toString(),
                "--checker",
                "LostMessages",
                "--repeats",
                "1",
                "--publishers",
                "2",
                "--duration",
                "60",
                "--max-length",
                "100",
                "--out",
                dir.resolve("m").toString()))
        .isZero();
    assertThat(out.toString())
        .contains(
            "minimal scenario",
            "0 of 2 fault(s), 1 publisher(s), 20 s",
            "replay: rmq-verify replay --plan",
            "--max-length 100");
    FaultPlan minimized =
        new FaultPlanCodec(NemesisRegistry.load()).read(dir.resolve("m/minimized-plan.json"));
    assertThat(minimized.events()).isEmpty();
  }

  @Test
  void minimizeRefusesAScenarioThatDoesNotFail(@TempDir Path dir) throws Exception {
    Path plan = dir.resolve("plan.json");
    new FaultPlanCodec(NemesisRegistry.load())
        .write(
            new FaultPlan(1, 5, 10_000, List.of("rabbit1", "rabbit2", "rabbit3"), List.of()), plan);
    MinimizeCommand minimize = new MinimizeCommand();
    minimize.experiments = fake(false);
    assertThat(
            execute(
                minimize,
                "--plan",
                plan.toString(),
                "--checker",
                "LostMessages",
                "--repeats",
                "2",
                "--duration",
                "10",
                "--out",
                dir.resolve("m").toString()))
        .isEqualTo(ExitCodes.FAILURE);
    assertThat(out.toString()).contains("does not reproduce", "repetition 2: not reproduced");
  }

  @ParameterizedTest
  @ValueSource(strings = {"cluster", "workload", "interrupted"})
  void oracleFailuresKeepTheirType(String kind) {
    Minimizer minimizer =
        new Minimizer(
            (candidate, publishers, duration) -> {
              switch (kind) {
                case "cluster" -> throw new ClusterException("down");
                case "workload" -> throw new WorkloadException("no queue");
                default -> throw new InterruptedException("stop");
              }
            });
    FaultPlan plan = new FaultPlan(1, 1, 10_000, List.of("rabbit1"), List.of());
    Class<? extends Exception> expected =
        switch (kind) {
          case "cluster" -> ClusterException.class;
          case "workload" -> WorkloadException.class;
          default -> InterruptedException.class;
        };
    assertThatThrownBy(() -> minimizer.minimize(plan, 1, Duration.ofSeconds(10)))
        .isInstanceOf(expected);
    assertThat(Thread.interrupted()).isEqualTo(kind.equals("interrupted"));
  }

  @Test
  void reportReadsCompressedHistories(@TempDir Path dir) throws Exception {
    RunCommand run = new RunCommand();
    run.experiments = fake(false);
    Path runDir = dir.resolve("run");
    execute(run, "--duration", "5", "--out", runDir.toString());
    Path history = runDir.resolve("history.jsonl");
    try (var in = Files.newInputStream(history);
        var gz =
            new java.util.zip.GZIPOutputStream(
                Files.newOutputStream(runDir.resolve("history.jsonl.gz")))) {
      in.transferTo(gz);
    }
    Files.delete(history);
    assertThat(execute(new ReportCommand(), "--run", runDir.toString())).isZero();
  }

  @Test
  void ioErrorsAreReportedAsFailures(@TempDir Path dir) {
    RunCommand run = new RunCommand();
    run.experiments =
        (env, writer) -> {
          throw new ClusterException("docker not running");
        };
    assertThat(execute(run, "--duration", "5", "--out", dir.toString()))
        .isEqualTo(ExitCodes.FAILURE);
    assertThat(out.toString()).contains("rmq-verify: docker not running");
  }
}
