package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.cluster.EnvFile;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.jsonl.JsonlHistoryReader;
import io.github.andassa.rmqverify.report.Matrix;
import io.github.andassa.rmqverify.report.ReportJson;
import io.github.andassa.rmqverify.report.ResultsPage;
import io.github.andassa.rmqverify.report.RunReport;
import io.github.andassa.rmqverify.report.RunSummary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code report} re-renders one run, or aggregates many runs into {@code RESULTS.md}. */
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
      names = "--results-md",
      defaultValue = "RESULTS.md",
      description = "Output of --matrix (default: ${DEFAULT-VALUE}).")
  Path resultsMarkdown;

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
      Path dir = target.run;
      RunSummary summary = new ReportJson().read(dir.resolve("report.json"));
      Path history =
          Files.exists(dir.resolve("history.jsonl"))
              ? dir.resolve("history.jsonl")
              : dir.resolve("history.jsonl.gz");
      History h = new JsonlHistoryReader().read(history);
      new RunReport(summary, h).writeAll(dir);
      spec.commandLine().getOut().println("report: " + dir.resolve("report.html").toAbsolutePath());
      return ExitCodes.of(summary.overall());
    }
    Matrix matrix = Matrix.load(target.matrix);
    ResultsPage page =
        new ResultsPage(Instant.now().toString(), commit, EnvFile.read(images), host);
    Files.writeString(resultsMarkdown, page.markdown(matrix), StandardCharsets.UTF_8);
    spec.commandLine()
        .getOut()
        .printf(
            "%s: %d runs, %d scenarios%n",
            resultsMarkdown, matrix.runs().size(), matrix.scenarios().size());
    return ExitCodes.VALID;
  }
}
