package io.github.andassa.rmqverify.report;

import static io.github.andassa.rmqverify.model.testkit.HistoryFixture.msg;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.andassa.rmqverify.model.Anomaly;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Severity;
import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.testkit.HistoryFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportRenderingTest {

  private static History history() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Message a = msg("p", "q", 0);
    f.published(a, OpType.OK);
    f.consumed("c", a, false, 1);
    f.advance(Duration.ofMillis(1200));
    f.faultStart(0, "kill-node", "rabbit2");
    f.published(msg("p", "q", 1), OpType.INFO);
    f.published(msg("p", "q", 2), OpType.FAIL);
    f.advance(Duration.ofSeconds(1));
    f.faultStop(0, "kill-node", "rabbit2");
    f.faultStart(1, "pause-node", "rabbit3");
    f.published(msg("p", "q", 3), OpType.OK);
    return f.build();
  }

  private static RunSummary summary(boolean invalid) {
    List<Verdict> verdicts = new ArrayList<>();
    verdicts.add(
        Verdict.of(
            "Availability",
            true,
            "ok",
            List.of(),
            Map.of("recoveryMs.0", 3.0, "maxRecoveryMs", 3.0, "successRate", 0.5)));
    List<Anomaly> anomalies = new ArrayList<>();
    if (invalid) {
      for (int i = 0; i < RunReport.MAX_LISTED + 2; i++) {
        anomalies.add(
            new Anomaly(
                "LOST_CONFIRMED",
                Severity.VIOLATION,
                "lost <b>" + i + "</b> | pipe",
                List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L)));
      }
    }
    anomalies.add(new Anomaly("NOTE_KIND", Severity.NOTE, "a note", List.of()));
    verdicts.add(
        Verdict.of(
            "LostMessages",
            true,
            "summary",
            anomalies,
            Map.of(
                "indeterminate",
                1.0,
                "indeterminateSurvived",
                0.0,
                "lostConfirmed",
                invalid ? 27.0 : 0.0,
                "confirmed",
                2.0)));
    verdicts.add(Verdict.of("SplitBrainIndicators", false, "no probes", List.of(), Map.of()));
    return new RunSummary(
        "r-<1>",
        "quorum-kill",
        1,
        "4.4.0",
        "QUORUM",
        7,
        "2026-10-24T10:00:00Z",
        60_000,
        new TreeMap<>(Map.of("publishers", "1")),
        List.of(
            new FaultSummary(0, "kill-node", 1000, 1000, List.of("rabbit2"), "targets=[rabbit2]"),
            new FaultSummary(1, "pause-node", 3000, 1000, List.of("rabbit3"), "targets=[rabbit3]")),
        verdicts);
  }

  @Test
  void seriesBucketsOutcomesLatenciesAndFaults() {
    Series series = Series.of(history());
    assertThat(series.seconds()).isEqualTo(3);
    assertThat(series.confirmed()).containsExactly(1, 0, 1);
    assertThat(series.indeterminate()).containsExactly(0, 1, 0);
    assertThat(series.failed()).containsExactly(0, 1, 0);
    assertThat(series.delivered()).containsExactly(1, 0, 0);
    assertThat(series.latencyP50Millis()[0]).isEqualTo(1.0);
    assertThat(series.latencyP99Millis()[1]).isNaN();
    assertThat(series.faults())
        .extracting(Series.Window::nemesis)
        .containsExactly("kill-node", "pause-node");
    assertThat(series.faults().get(0).endSeconds())
        .isGreaterThan(series.faults().get(0).startSeconds());
    assertThat(series.totalConfirmed()).isEqualTo(2);
    assertThat(series.toString()).contains("confirmed=2");
    long[] copy = series.confirmed();
    copy[0] = 99;
    assertThat(series.confirmed()[0]).isEqualTo(1);
    assertThat(Series.of(HistoryFixture.quorum("q").build()).seconds()).isZero();
    assertThat(Series.percentile(List.of(5.0, 1.0, 3.0), 0.5)).isEqualTo(3.0);
    assertThat(Series.percentile(List.of(5.0, 1.0, 3.0), 0.99)).isEqualTo(5.0);
  }

  @Test
  void htmlIsSelfContainedAndEscaped() {
    History history = history();
    String html = new RunReport(summary(true), history).html();
    assertThat(html)
        .startsWith("<!DOCTYPE html>")
        .contains(
            "<svg",
            "Overall: INVALID",
            "r-&lt;1&gt;",
            "lost &lt;b&gt;0&lt;/b&gt;",
            "What this run proves",
            "Proven: LostMessages",
            "Indeterminate:");
    assertThat(html).doesNotContain("<script", "https://cdn", "<b>0</b>", "src=\"http");
    assertThat(html.replace("http://www.w3.org/2000/svg", "")).doesNotContain("http://");
  }

  @Test
  void markdownListsVerdictsAnomaliesAndLimits() {
    History history = history();
    String md = new RunReport(summary(true), history).markdown();
    assertThat(md)
        .contains(
            "# rmq-verify run r-&lt;1&gt;",
            "**Overall: INVALID**",
            "| LostMessages | INVALID | 27 |",
            "## Fault plan",
            "| 0 | kill-node | 1.0 | 1.0 |",
            "![throughput.svg](throughput.svg)",
            "... 3 more in report.json",
            "lost &lt;b&gt;0&lt;/b&gt; | pipe",
            "[evidence: #0 p INVOKE PUBLISH @0.000s;",
            ", ...]",
            "Indeterminate: 1 publish(es)",
            "Limits: one run of 60 s");
    String clean = new RunReport(summary(false), history).markdown();
    assertThat(clean).contains("**Overall: UNKNOWN**").doesNotContain("Proven:");
  }

  @Test
  void controlRunWithoutFaultsOrAnomalies() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.published(msg("p", "q", 0), OpType.OK);
    History history = f.build();
    RunSummary summary =
        new RunSummary(
            "r",
            "s",
            1,
            "3.13.7",
            "QUORUM",
            1,
            "t",
            5000,
            new TreeMap<>(),
            List.of(),
            List.of(Verdict.of("LostMessages", true, "", List.of(), Map.of())));
    RunReport report = new RunReport(summary, history);
    assertThat(report.markdown()).contains("No fault: control run.", "## Anomalies\n\nNone.");
    assertThat(report.charts()).containsOnlyKeys("throughput.svg", "latency.svg", "recovery.svg");
  }

  @Test
  void writeAllProducesEveryArtefact(@TempDir Path dir) throws Exception {
    History history = history();
    new RunReport(summary(false), history).writeAll(dir.resolve("out"));
    assertThat(dir.resolve("out"))
        .isDirectoryContaining(p -> p.getFileName().toString().equals("report.html"));
    for (String name :
        List.of("report.md", "report.json", "throughput.svg", "latency.svg", "recovery.svg")) {
      assertThat(dir.resolve("out").resolve(name)).exists();
    }
    assertThat(Files.readString(dir.resolve("out/recovery.svg"))).contains("#0 kill-node", "3 ms");
  }

  @Test
  void svgHelpers() {
    assertThat(Svg.niceCeiling(7)).isEqualTo(10);
    assertThat(Svg.niceCeiling(130)).isEqualTo(200);
    assertThat(Svg.niceCeiling(400)).isEqualTo(500);
    assertThat(Svg.niceCeiling(1000)).isEqualTo(1000);
    assertThat(
            Svg.lineChart(
                "t<", "u", 0, List.of(Svg.Line.of("a\"b", "#000", new double[] {1, 2})), List.of()))
        .contains("t&lt;", "a&quot;b", "<polyline");
    assertThat(Svg.barChart("bars", "ms", List.of(), new double[0]))
        .contains("bars")
        .endsWith("</svg>");
    assertThat(Html.escape("<&>\"'")).isEqualTo("&lt;&amp;&gt;&quot;&#39;");
  }

  @Test
  void matrixAggregatesRepetitionsAndResultsPageRendersIt(@TempDir Path dir) throws Exception {
    RunSummary invalid = summary(true);
    RunSummary valid = summary(false);
    ReportJson json = new ReportJson();
    Files.createDirectories(dir.resolve("a"));
    Files.createDirectories(dir.resolve("b"));
    json.write(invalid, dir.resolve("a/report.json"));
    json.write(valid, dir.resolve("b/report.json"));
    Matrix matrix = Matrix.load(dir);
    assertThat(matrix.scenarios()).containsExactly("quorum-kill");
    assertThat(matrix.versions()).containsExactly("4.4.0");
    MatrixCell cell = matrix.cell("quorum-kill", "4.4.0").orElseThrow();
    assertThat(cell.runs()).isEqualTo(2);
    assertThat(cell.invalidRuns()).isEqualTo(1);
    assertThat(cell.unknownRuns()).isEqualTo(1);
    assertThat(cell.lostConfirmed()).isEqualTo(27);
    assertThat(cell.confirmed()).isEqualTo(4);
    assertThat(cell.maxRecoveryMs()).isEqualTo(3.0);
    assertThat(cell.minSuccessRate()).isEqualTo(0.5);
    assertThat(matrix.cell("quorum-kill", "3.13.7")).isEmpty();
    ResultsPage page =
        new ResultsPage(
            "2026-10-24T12:00:00Z",
            "abc1234",
            Map.of("rabbitmq", "rabbitmq:4.4.0@sha256:x"),
            "test host");
    String md = page.markdown(matrix);
    assertThat(md)
        .contains(
            "# Results",
            "commit `abc1234`",
            "Image rabbitmq: `rabbitmq:4.4.0@sha256:x`",
            "| `quorum-kill` | **1/2** invalid · lost 27",
            "## Counterexamples",
            "run `r-<1>`, LostMessages: 27 violation(s)",
            "How to read these results");
    assertThat(page.markdown(Matrix.of(List.of(valid)))).contains("No violation was observed");
    String readme =
        "# Title\n" + ResultsPage.START + "\nold numbers\n" + ResultsPage.END + "\nrest\n";
    String updated = page.updateBlock(readme, matrix);
    assertThat(updated)
        .startsWith("# Title\n" + ResultsPage.START)
        .endsWith(ResultsPage.END + "\nrest\n")
        .contains("| `quorum-kill` | **1/2** invalid", "commit `abc1234`")
        .doesNotContain("old numbers", "## Matrix");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> page.updateBlock("no markers", matrix))
        .hasMessageContaining("markers");
  }
}
