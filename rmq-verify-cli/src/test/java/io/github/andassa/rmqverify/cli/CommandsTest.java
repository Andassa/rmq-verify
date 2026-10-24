package io.github.andassa.rmqverify.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.checker.CheckerContext;
import io.github.andassa.rmqverify.checker.Checkers;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.VerdictStatus;
import io.github.andassa.rmqverify.model.jsonl.JsonlHistoryReader;
import io.github.andassa.rmqverify.model.jsonl.JsonlHistoryWriter;
import io.github.andassa.rmqverify.model.testkit.HistoryFixture;
import io.github.andassa.rmqverify.report.ReportJson;
import io.github.andassa.rmqverify.report.RunSummary;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CommandsTest {

  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();

  private int execute(String... args) {
    CommandLine cli = Main.commandLine();
    cli.setOut(new PrintWriter(out));
    cli.setErr(new PrintWriter(err));
    return cli.execute(args);
  }

  private static Path history(Path dir, boolean lose) throws Exception {
    HistoryFixture f = HistoryFixture.quorum("rmqv.q");
    Message kept = HistoryFixture.msg("p", "rmqv.q", 0);
    Message lost = HistoryFixture.msg("p", "rmqv.q", 1);
    f.published(kept, OpType.OK);
    f.published(lost, OpType.OK);
    f.consumed("c", kept, false, 1);
    if (!lose) {
      f.consumed("c", lost, false, 2);
    }
    f.drainStart("rmqv.q");
    f.drainEnd("rmqv.q", OpType.OK, 0);
    f.probe(0, "rabbit1", "cluster", "", java.util.List.of("rabbit1"), java.util.List.of());
    History h = f.build();
    Path file = dir.resolve(lose ? "lost.jsonl.gz" : "ok.jsonl");
    try (JsonlHistoryWriter writer = JsonlHistoryWriter.open(file, h.header())) {
      for (var op : h.operations()) {
        writer.accept(op);
      }
    }
    return file;
  }

  @Test
  void checkReturnsZeroForAValidHistoryAndOneForALoss(@TempDir Path dir) throws Exception {
    assertThat(execute("check", history(dir, false).toString())).isEqualTo(ExitCodes.VALID);
    assertThat(out.toString()).contains("LostMessages", "VALID", "run run-1");
    assertThat(execute("check", history(dir, true).toString())).isEqualTo(ExitCodes.INVALID);
    assertThat(
            execute(
                "check", "--checker", "FifoOrdering,LostMessages", history(dir, true).toString()))
        .isEqualTo(ExitCodes.INVALID);
  }

  @Test
  void checkReportsUnknownCheckersAndUnreadableFiles(@TempDir Path dir) throws Exception {
    assertThat(execute("check", "--checker", "Nope", history(dir, false).toString()))
        .isEqualTo(ExitCodes.FAILURE);
    assertThat(err.toString()).contains("unknown checker Nope");
    Path garbage = Files.writeString(dir.resolve("garbage.jsonl"), "{not json\n");
    assertThat(execute("check", garbage.toString())).isEqualTo(ExitCodes.FAILURE);
    assertThat(execute("check")).isEqualTo(ExitCodes.USAGE);
  }

  @Test
  void planIsDeterministic(@TempDir Path dir) throws Exception {
    assertThat(
            execute(
                "plan",
                "--seed",
                "42",
                "--duration",
                "120",
                "--nemesis",
                "kill-node,partition-halves"))
        .isZero();
    String first = out.toString();
    out.getBuffer().setLength(0);
    execute("plan", "--seed", "42", "--duration", "120", "--nemesis", "kill-node,partition-halves");
    assertThat(out.toString()).isEqualTo(first).contains("\"seed\" : 42");
    Path file = dir.resolve("plan.json");
    assertThat(
            execute(
                "plan",
                "--seed",
                "42",
                "--duration",
                "120",
                "--nemesis",
                "kill-node,partition-halves",
                "--out",
                file.toString()))
        .isZero();
    assertThat(Files.readString(file)).isEqualTo(first);
    assertThat(execute("plan", "--seed", "1", "--nemesis", "meteor")).isEqualTo(ExitCodes.FAILURE);
  }

  @Test
  void runFailsCleanlyWithoutCredentials(@TempDir Path dir) {
    assertThat(
            execute(
                "run",
                "--env-file",
                dir.resolve("missing.env").toString(),
                "--duration",
                "5",
                "--out",
                dir.resolve("run").toString()))
        .isEqualTo(ExitCodes.FAILURE);
    assertThat(err.toString()).contains("RMQV_ADMIN_USER");
    assertThat(execute("replay", "--plan", dir.resolve("absent.json").toString()))
        .isEqualTo(ExitCodes.FAILURE);
  }

  @Test
  void reportRendersARunAndTheMatrix(@TempDir Path dir) throws Exception {
    Path run = Files.createDirectories(dir.resolve("results/4.4.0/s-1"));
    Files.copy(history(dir, false), run.resolve("history.jsonl"));
    History h = new JsonlHistoryReader().read(run.resolve("history.jsonl"));
    RunSummary summary =
        new RunSummary(
            "r-1",
            "s",
            1,
            "4.4.0",
            "QUORUM",
            1,
            "t",
            5000,
            new TreeMap<>(),
            List.of(),
            Checkers.load().checkAll(h, CheckerContext.defaults()));
    new ReportJson().write(summary, run.resolve("report.json"));
    assertThat(execute("report", "--run", run.toString()))
        .isEqualTo(ExitCodes.of(summary.overall()));
    assertThat(run.resolve("report.html")).exists();
    Path images =
        Files.writeString(dir.resolve("images.env"), "RABBITMQ_4_4=rabbitmq:4.4.0@sha256:abc\n");
    Path results = dir.resolve("RESULTS.md");
    assertThat(
            execute(
                "report",
                "--matrix",
                dir.resolve("results").toString(),
                "--results-md",
                results.toString(),
                "--images",
                images.toString(),
                "--commit",
                "deadbee"))
        .isZero();
    assertThat(Files.readString(results))
        .contains("commit `deadbee`", "`s`", "rabbitmq:4.4.0@sha256:abc");
    assertThat(execute("report")).isEqualTo(ExitCodes.USAGE);
  }

  @Test
  void minimizeNeedsAPlanAndCredentials(@TempDir Path dir) throws Exception {
    assertThat(execute("minimize", "--checker", "LostMessages")).isEqualTo(ExitCodes.USAGE);
    Path plan = dir.resolve("plan.json");
    execute("plan", "--seed", "3", "--nemesis", "kill-node", "--out", plan.toString());
    assertThat(
            execute(
                "minimize",
                "--plan",
                plan.toString(),
                "--checker",
                "LostMessages",
                "--env-file",
                dir.resolve("none.env").toString(),
                "--out",
                dir.resolve("m").toString()))
        .isEqualTo(ExitCodes.FAILURE);
    assertThat(err.toString()).contains("RMQV_ADMIN_USER");
  }

  @Test
  void versionAndHelp() {
    assertThat(execute("--version")).isZero();
    assertThat(out.toString()).contains("rmq-verify");
    assertThat(execute("--help")).isZero();
    assertThat(out.toString()).contains("run, replay", "plan", "check");
  }

  @Test
  void runOptionsAreValidated() {
    RunOptions options = new RunOptions();
    options.queueType = io.github.andassa.rmqverify.model.QueueKind.STREAM;
    options.queues = 0;
    options.durationSeconds = 60;
    assertThatThrownBy(() -> options.settings(1)).hasMessageContaining("queues must be 1-16");
    options.queues = 2;
    options.durationSeconds = 2;
    assertThatThrownBy(() -> options.settings(1)).hasMessageContaining("duration");
    options.durationSeconds = 30;
    options.settleSeconds = -1;
    assertThatThrownBy(() -> options.settings(1)).hasMessageContaining("settle");
    options.settleSeconds = 10;
    options.maxRecoverySeconds = 60;
    options.scenario = "s";
    options.publishers = 1;
    options.consumers = 1;
    options.rate = 5;
    options.payloadBytes = 300;
    options.confirmTimeoutMillis = 100;
    options.drainTimeoutSeconds = 10;
    ExperimentSettings settings = options.settings(0xabcd);
    assertThat(settings.runId()).matches("r-\\d{8}-\\d{6}-abcd");
    assertThat(settings.outputDir().toString()).startsWith("runs");
  }

  @Test
  void exitCodesFollowTheVerdicts() {
    assertThat(ExitCodes.of(VerdictStatus.VALID)).isZero();
    assertThat(ExitCodes.of(VerdictStatus.INVALID)).isEqualTo(1);
    assertThat(ExitCodes.of(VerdictStatus.UNKNOWN)).isEqualTo(3);
  }
}
