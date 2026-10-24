package io.github.andassa.rmqverify.report;

import io.github.andassa.rmqverify.model.Anomaly;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.VerdictStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders one run as {@code report.md}, {@code report.html}, {@code report.json} and SVG charts.
 */
public final class RunReport {

  /** Anomalies listed per checker; the JSON report always holds all of them. */
  static final int MAX_LISTED = 25;

  private final RunSummary summary;
  private final History history;
  private final Series series;

  /**
   * Prepares the report of a run.
   *
   * @param summary configuration, faults and verdicts
   * @param history the recorded history
   */
  public RunReport(RunSummary summary, History history) {
    this.summary = summary;
    this.history = history;
    this.series = Series.of(history);
  }

  /**
   * Writes every artefact into a directory.
   *
   * @param directory destination, created if needed
   * @throws IOException if a file cannot be written
   */
  public void writeAll(Path directory) throws IOException {
    Files.createDirectories(directory);
    new ReportJson().write(summary, directory.resolve("report.json"));
    for (Map.Entry<String, String> chart : charts().entrySet()) {
      Files.writeString(
          directory.resolve(chart.getKey()), chart.getValue(), StandardCharsets.UTF_8);
    }
    Files.writeString(directory.resolve("report.md"), markdown(), StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("report.html"), html(), StandardCharsets.UTF_8);
  }

  /**
   * Renders the charts.
   *
   * @return SVG documents by file name, in display order
   */
  public Map<String, String> charts() {
    Map<String, String> charts = new LinkedHashMap<>();
    charts.put(
        "throughput.svg",
        Svg.lineChart(
            "Operations per second (shaded: faults)",
            "ops/s",
            series.seconds(),
            List.of(
                Svg.Line.of("confirmed", "#2b8a3e", series.confirmed()),
                Svg.Line.of("delivered", "#1c7ed6", series.delivered()),
                Svg.Line.of("indeterminate", "#e67700", series.indeterminate()),
                Svg.Line.of("failed", "#c92a2a", series.failed())),
            series.faults()));
    charts.put(
        "latency.svg",
        Svg.lineChart(
            "Publish to confirm latency (shaded: faults)",
            "ms",
            series.seconds(),
            List.of(
                Svg.Line.of("p50", "#5f3dc4", series.latencyP50Millis()),
                Svg.Line.of("p99", "#d6336c", series.latencyP99Millis())),
            series.faults()));
    List<String> labels = new ArrayList<>();
    List<Double> recoveries = new ArrayList<>();
    for (FaultSummary fault : summary.faults()) {
      Double ms = metric("Availability", "recoveryMs." + fault.index());
      if (ms != null) {
        labels.add("#" + fault.index() + " " + fault.nemesis());
        recoveries.add(ms);
      }
    }
    charts.put(
        "recovery.svg",
        Svg.barChart(
            "Time to the first confirmed publish after each heal",
            "ms",
            labels,
            recoveries.stream().mapToDouble(Double::doubleValue).toArray()));
    return charts;
  }

  /**
   * Renders the Markdown report.
   *
   * @return Markdown text referencing the SVG files
   */
  public String markdown() {
    StringBuilder md = new StringBuilder();
    md.append("# rmq-verify run ").append(text(summary.runId())).append("\n\n");
    md.append("**Overall: ").append(summary.overall()).append("**\n\n");
    md.append("| Setting | Value |\n|---|---|\n");
    configuration()
        .forEach((k, v) -> md.append("| ").append(k).append(" | ").append(cell(v)).append(" |\n"));
    md.append("\n## Fault plan\n\n");
    if (summary.faults().isEmpty()) {
      md.append("No fault: control run.\n");
    } else {
      md.append("| # | Nemesis | Start (s) | Duration (s) | Parameters |\n|---|---|---|---|---|\n");
      for (FaultSummary f : summary.faults()) {
        md.append(
            String.format(
                Locale.ROOT,
                "| %d | %s | %.1f | %.1f | %s |%n",
                f.index(),
                f.nemesis(),
                f.startMillis() / 1000.0,
                f.durationMillis() / 1000.0,
                cell(f.detail())));
      }
    }
    md.append("\n## Verdicts\n\n| Checker | Verdict | Violations | Summary |\n|---|---|---|---|\n");
    for (Verdict v : summary.verdicts()) {
      md.append("| ")
          .append(v.checker())
          .append(" | ")
          .append(v.status())
          .append(" | ")
          .append(v.violationCount())
          .append(" | ")
          .append(cell(v.summary()))
          .append(" |\n");
    }
    md.append("\n## Charts\n\n");
    charts()
        .keySet()
        .forEach(name -> md.append("![").append(name).append("](").append(name).append(")\n\n"));
    md.append("## Anomalies\n\n");
    boolean any = false;
    for (Verdict v : summary.verdicts()) {
      if (v.anomalies().isEmpty()) {
        continue;
      }
      any = true;
      md.append("### ").append(v.checker()).append("\n\n");
      for (Anomaly a : listed(v)) {
        md.append("- **")
            .append(a.kind())
            .append("** (")
            .append(a.severity())
            .append("): ")
            .append(text(a.description()))
            .append(text(evidence(a)))
            .append('\n');
      }
      if (v.anomalies().size() > MAX_LISTED) {
        md.append("- ... ")
            .append(v.anomalies().size() - MAX_LISTED)
            .append(" more in report.json\n");
      }
      md.append('\n');
    }
    if (!any) {
      md.append("None.\n\n");
    }
    md.append("## What this run proves\n\n");
    conclusions().forEach(line -> md.append("- ").append(text(line)).append('\n'));
    return md.toString();
  }

  /**
   * Renders the self-contained HTML report.
   *
   * @return an HTML document without external resources
   */
  public String html() {
    StringBuilder h =
        new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
    h.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
    h.append("<title>rmq-verify ")
        .append(Html.escape(summary.runId()))
        .append("</title><style>")
        .append(HtmlStyle.CSS)
        .append("</style></head><body><main>");
    h.append("<h1>rmq-verify run ").append(Html.escape(summary.runId())).append("</h1>");
    h.append("<p class=\"overall ")
        .append(summary.overall().name().toLowerCase(Locale.ROOT))
        .append("\">Overall: ")
        .append(summary.overall())
        .append("</p>");
    h.append("<table><tbody>");
    configuration()
        .forEach(
            (k, v) ->
                h.append("<tr><th>")
                    .append(Html.escape(k))
                    .append("</th><td>")
                    .append(Html.escape(v))
                    .append("</td></tr>"));
    h.append(
            "</tbody></table><h2>Verdicts</h2><table><thead><tr><th>Checker</th><th>Verdict</th><th>Violations</th>")
        .append("<th>Summary</th></tr></thead><tbody>");
    for (Verdict v : summary.verdicts()) {
      h.append("<tr><td>")
          .append(Html.escape(v.checker()))
          .append("</td><td class=\"")
          .append(v.status().name().toLowerCase(Locale.ROOT))
          .append("\">")
          .append(v.status())
          .append("</td><td>")
          .append(v.violationCount())
          .append("</td><td>")
          .append(Html.escape(v.summary()))
          .append("</td></tr>");
    }
    h.append("</tbody></table><h2>Timeline and charts</h2>");
    charts().values().forEach(svg -> h.append("<figure>").append(svg).append("</figure>"));
    h.append("<h2>Fault plan</h2><ol start=\"0\">");
    for (FaultSummary f : summary.faults()) {
      h.append("<li>")
          .append(
              Html.escape(
                  String.format(
                      Locale.ROOT,
                      "%s at %.1f s for %.1f s, %s",
                      f.nemesis(),
                      f.startMillis() / 1000.0,
                      f.durationMillis() / 1000.0,
                      f.detail())))
          .append("</li>");
    }
    h.append("</ol><h2>Anomalies</h2>");
    for (Verdict v : summary.verdicts()) {
      if (!v.anomalies().isEmpty()) {
        h.append("<h3>").append(Html.escape(v.checker())).append("</h3><ul>");
        for (Anomaly a : listed(v)) {
          h.append("<li class=\"")
              .append(a.isViolation() ? "invalid" : "note")
              .append("\"><b>")
              .append(Html.escape(a.kind()))
              .append("</b> ")
              .append(Html.escape(a.description()))
              .append(Html.escape(evidence(a)))
              .append("</li>");
        }
        h.append("</ul>");
      }
    }
    h.append("<h2>What this run proves</h2><ul>");
    conclusions().forEach(line -> h.append("<li>").append(Html.escape(line)).append("</li>"));
    return h.append("</ul></main></body></html>\n").toString();
  }

  private Map<String, String> configuration() {
    Map<String, String> config = new LinkedHashMap<>();
    config.put("Scenario", summary.scenario() + " (repetition " + summary.repetition() + ")");
    config.put("RabbitMQ", summary.rabbitmqVersion());
    config.put("Queue type", summary.queueKind());
    config.put("Seed", Long.toString(summary.seed()));
    config.put("Started", summary.startedAt());
    config.put("Operations", Integer.toString(history.size()));
    summary.settings().forEach(config::put);
    return config;
  }

  private List<String> conclusions() {
    List<String> lines = new ArrayList<>();
    for (Verdict v : summary.verdicts()) {
      if (v.status() == VerdictStatus.INVALID) {
        lines.add(
            "Proven: "
                + v.checker()
                + " found "
                + v.violationCount()
                + " violation(s); the evidence operations are in history.jsonl and `rmq-verify check` reproduces them.");
      } else if (v.status() == VerdictStatus.UNKNOWN) {
        lines.add("Indeterminate: " + v.checker() + " could not conclude (" + v.summary() + ").");
      }
    }
    Double indeterminate = metric("LostMessages", "indeterminate");
    if (indeterminate != null && indeterminate > 0) {
      lines.add(
          String.format(
              Locale.ROOT,
              "Indeterminate: %.0f publish(es) ended without a confirm; %.0f of them were "
                  + "delivered anyway, which shows that INFO is not a failure.",
              indeterminate,
              metric("LostMessages", "indeterminateSurvived")));
    }
    lines.add(
        "Not proven: a VALID verdict only says that this run, with this plan, did not exhibit the anomaly.");
    lines.add(
        String.format(
            Locale.ROOT,
            "Limits: one run of %d s on a 3-node cluster; the seed reproduces the plan "
                + "and the workload, not the broker's timing; the client reaches every node through Toxiproxy.",
            summary.durationMillis() / 1000));
    return lines;
  }

  private Double metric(String checker, String name) {
    return summary.verdicts().stream()
        .filter(v -> v.checker().equals(checker))
        .findFirst()
        .map(v -> v.metrics().get(name))
        .orElse(null);
  }

  private static List<Anomaly> listed(Verdict v) {
    List<Anomaly> sorted = new ArrayList<>(v.anomalies());
    sorted.sort((a, b) -> Boolean.compare(b.isViolation(), a.isViolation()));
    return sorted.subList(0, Math.min(MAX_LISTED, sorted.size()));
  }

  private String evidence(Anomaly a) {
    if (a.evidence().isEmpty()) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    for (long id : a.evidence().subList(0, Math.min(6, a.evidence().size()))) {
      history
          .byId(id)
          .ifPresent(
              (Operation op) ->
                  parts.add(
                      String.format(
                          Locale.ROOT,
                          "#%d %s %s %s @%.3fs",
                          id,
                          op.process(),
                          op.type(),
                          op.function(),
                          series.offsetSeconds(op.timestampNanos()))));
    }
    String more = a.evidence().size() > 6 ? ", ..." : "";
    return " [evidence: " + String.join("; ", parts) + more + "]";
  }

  /** GitHub renders inline HTML in Markdown, so text that comes from data is escaped. */
  static String text(String raw) {
    return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String cell(String raw) {
    return text(raw).replace("|", "\\|").replace("\n", " ");
  }
}
