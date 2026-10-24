package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.checker.CheckerContext;
import io.github.andassa.rmqverify.checker.Checkers;
import io.github.andassa.rmqverify.cluster.EnvFile;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.jsonl.JsonlHistoryReader;
import io.github.andassa.rmqverify.report.Matrix;
import io.github.andassa.rmqverify.report.ReportJson;
import io.github.andassa.rmqverify.report.ResultsPage;
import io.github.andassa.rmqverify.report.RunReport;
import io.github.andassa.rmqverify.report.RunSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code report} re-renders one run, or aggregates many runs into {@code RESULTS.md}. With {@code
 * --recheck}, verdicts are recomputed from the archived histories with the current checkers, which
 * is possible because a verdict is a pure function of its history.
 */
@Command(
    name = "report",
    mixinStandardHelpOptions = true,
    description = "Render a run report, or the results matrix of a directory of runs.")
public final class ReportCommand implements Callable<Integer> {

  @ArgGroup(multiplicity = "1")
  Target target;

  static final class Target {
    @Option(
        names = "--run",
        description = "Run directory holding history.jsonl[.gz] and report.json.")
    Path run;

    @Option(names = "--matrix", description = "Directory scanned for report.json files.")
    Path matrix;
  }

  @Option(
      names = "--recheck",
      description = "Recompute the verdicts from the archived histories with the current checkers.")
  boolean recheck;

  @Option(
      names = "--results-md",
      defaultValue = "RESULTS.md",
      description = "Output of --matrix (default: ${DEFAULT-VALUE}).")
  Path resultsMarkdown;

  @Option(names = "--readme", description = "Also refresh the results block of this README.")
  Path readme;

  @Option(
      names = "--commit",
      defaultValue = "unknown",
      description = "Commit recorded on the results page.")
  String commit;

  @Option(
      names = "--images",
      defaultValue = "deploy/images.env",
      description = "Pinned images file (default: ${DEFAULT-VALUE}).")
  Path images;

  @Option(
      names = "--host",
      defaultValue = "unspecified",
      description = "Machine description recorded on the page.")
  String host;

  @Spec CommandSpec spec;

  @Override
  public Integer call() throws Exception {
    if (target.run != null) {
      RunSummary summary = render(target.run);
      spec.commandLine()
          .getOut()
          .println("report: " + target.run.resolve("report.html").toAbsolutePath());
      return ExitCodes.of(summary.overall());
    }
    if (recheck) {
      try (Stream<Path> reports = Files.walk(target.matrix, 4)) {
        for (Path report : reports.filter(p -> p.endsWith("report.json")).sorted().toList()) {
          Path dir = report.getParent();
          if (dir != null) {
            render(dir);
          }
        }
      }
    }
    Matrix matrix = Matrix.load(target.matrix);
    ResultsPage page =
        new ResultsPage(Instant.now().toString(), commit, EnvFile.read(images), host);
    Files.writeString(resultsMarkdown, page.markdown(matrix), StandardCharsets.UTF_8);
    if (readme != null) {
      String current = Files.readString(readme, StandardCharsets.UTF_8);
      Files.writeString(readme, page.updateBlock(current, matrix), StandardCharsets.UTF_8);
    }
    spec.commandLine()
        .getOut()
        .printf(
            "%s: %d runs, %d scenarios%n",
            resultsMarkdown, matrix.runs().size(), matrix.scenarios().size());
    return ExitCodes.VALID;
  }

  private RunSummary render(Path dir) throws IOException {
    RunSummary summary = new ReportJson().read(dir.resolve("report.json"));
    Path history =
        Files.exists(dir.resolve("history.jsonl"))
            ? dir.resolve("history.jsonl")
            : dir.resolve("history.jsonl.gz");
    History h = new JsonlHistoryReader().read(history);
    if (recheck) {
      summary =
          new RunSummary(
              summary.runId(),
              summary.scenario(),
              summary.repetition(),
              summary.rabbitmqVersion(),
              summary.queueKind(),
              summary.seed(),
              summary.startedAt(),
              summary.durationMillis(),
              summary.settings(),
              summary.faults(),
              Checkers.load().checkAll(h, CheckerContext.defaults()));
    }
    new RunReport(summary, h).writeAll(dir);
    return summary;
  }
}
