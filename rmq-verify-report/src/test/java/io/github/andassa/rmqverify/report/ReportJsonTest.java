package io.github.andassa.rmqverify.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.model.Anomaly;
import io.github.andassa.rmqverify.model.Severity;
import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.VerdictStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportJsonTest {

  static RunSummary summary(VerdictStatus... statuses) {
    List<Verdict> verdicts = new java.util.ArrayList<>();
    for (int i = 0; i < statuses.length; i++) {
      List<Anomaly> anomalies =
          statuses[i] == VerdictStatus.INVALID
              ? List.of(new Anomaly("LOST_CONFIRMED", Severity.VIOLATION, "lost", List.of(3L, 4L)))
              : List.of(new Anomaly("REDELIVERY", Severity.NOTE, "fine", List.of()));
      verdicts.add(
          new Verdict(
              "Checker" + i,
              statuses[i],
              "summary " + i,
              anomalies,
              new TreeMap<>(Map.of("lostConfirmed", 1.0))));
    }
    return new RunSummary(
        "r-1",
        "quorum-kill",
        2,
        "4.4.0",
        "QUORUM",
        42,
        "2026-10-24T10:00:00Z",
        60_000,
        new TreeMap<>(Map.of("publishers", "4")),
        List.of(
            new FaultSummary(
                0, "kill-node", 5000, 10_000, List.of("rabbit2"), "targets=[rabbit2]")),
        verdicts);
  }

  @Test
  void roundTripsThroughAFile(@TempDir Path dir) throws Exception {
    RunSummary summary = summary(VerdictStatus.VALID, VerdictStatus.INVALID);
    ReportJson json = new ReportJson();
    json.write(summary, dir.resolve("report.json"));
    assertThat(json.read(dir.resolve("report.json"))).isEqualTo(summary);
    assertThat(json.write(summary))
        .doesNotContain("\"violation\"")
        .contains("\"evidence\" : [ 3, 4 ]");
  }

  @Test
  void overallStatusIsTheWorstVerdict() {
    assertThat(summary(VerdictStatus.VALID, VerdictStatus.VALID).overall())
        .isEqualTo(VerdictStatus.VALID);
    assertThat(summary(VerdictStatus.VALID, VerdictStatus.UNKNOWN).overall())
        .isEqualTo(VerdictStatus.UNKNOWN);
    assertThat(summary(VerdictStatus.UNKNOWN, VerdictStatus.INVALID).overall())
        .isEqualTo(VerdictStatus.INVALID);
  }

  @Test
  void rejectsUnknownFieldsAndOversizedFiles(@TempDir Path dir) throws Exception {
    ReportJson json = new ReportJson();
    Path file = dir.resolve("report.json");
    Files.writeString(
        file,
        json.write(summary(VerdictStatus.VALID))
            .replace("\"runId\"", "\"surprise\" : 1, \"runId\""));
    assertThatThrownBy(() -> json.read(file)).hasMessageContaining("surprise");
    Files.writeString(
        file,
        json.write(summary(VerdictStatus.VALID))
            .replace("\"status\" : \"VALID\"", "\"status\" : \"INVALID\""));
    assertThatThrownBy(() -> json.read(file)).hasMessageContaining("inconsistent");
  }
}
