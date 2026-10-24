package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.report.ReportJson;
import io.github.andassa.rmqverify.report.RunSummary;
import java.io.IOException;
import java.nio.file.Path;

/** Writes the per-run reports next to {@code history.jsonl} and {@code plan.json}. */
final class RunArtifacts {

  private RunArtifacts() {}

  static void write(Path directory, RunSummary summary, History history) throws IOException {
    new ReportJson().write(summary, directory.resolve("report.json"));
  }
}
