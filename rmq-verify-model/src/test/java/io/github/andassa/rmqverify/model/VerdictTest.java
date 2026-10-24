package io.github.andassa.rmqverify.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class VerdictTest {

  private static final Anomaly VIOLATION =
      new Anomaly("LOST_CONFIRMED", Severity.VIOLATION, "lost", List.of(3L));
  private static final Anomaly NOTE = new Anomaly("REDELIVERY", Severity.NOTE, "ok", List.of());

  @Test
  void anomalyValidation() {
    assertThat(VIOLATION.isViolation()).isTrue();
    assertThat(NOTE.isViolation()).isFalse();
    assertThatThrownBy(() -> new Anomaly("lower", Severity.NOTE, "", List.of()))
        .hasMessageContaining("UPPER_SNAKE_CASE");
    assertThatThrownBy(() -> new Anomaly(null, Severity.NOTE, "", List.of()))
        .hasMessageContaining("UPPER_SNAKE_CASE");
    assertThatThrownBy(() -> new Anomaly("X", Severity.VIOLATION, "", List.of()))
        .hasMessageContaining("requires evidence");
    assertThatThrownBy(() -> new Anomaly("X", null, "", List.of()))
        .hasMessageContaining("severity");
    assertThatThrownBy(() -> new Anomaly("X", Severity.NOTE, "", null))
        .hasMessageContaining("evidence");
    assertThat(new Anomaly("A" + "B".repeat(63), Severity.NOTE, "", List.of()).kind()).hasSize(64);
    assertThatThrownBy(() -> new Anomaly("A" + "B".repeat(64), Severity.NOTE, "", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void statusFollowsAnomalies() {
    assertThat(Verdict.of("c", true, "", List.of(VIOLATION), Map.of()).status())
        .isEqualTo(VerdictStatus.INVALID);
    assertThat(Verdict.of("c", false, "", List.of(VIOLATION), Map.of()).status())
        .isEqualTo(VerdictStatus.INVALID);
    assertThat(Verdict.of("c", true, "", List.of(NOTE), Map.of()).status())
        .isEqualTo(VerdictStatus.VALID);
    assertThat(Verdict.of("c", false, "", List.of(NOTE), Map.of()).status())
        .isEqualTo(VerdictStatus.UNKNOWN);
  }

  @Test
  void inconsistentStatusIsRejected() {
    TreeMap<String, Double> metrics = new TreeMap<>();
    assertThatThrownBy(() -> new Verdict("c", VerdictStatus.VALID, "", List.of(VIOLATION), metrics))
        .hasMessageContaining("inconsistent");
    assertThatThrownBy(() -> new Verdict("c", VerdictStatus.INVALID, "", List.of(NOTE), metrics))
        .hasMessageContaining("inconsistent");
    assertThatThrownBy(() -> new Verdict("c", null, "", List.of(), metrics))
        .hasMessageContaining("status");
  }

  @Test
  void violationCountAndSortedMetrics() {
    Verdict verdict =
        Verdict.of("c", true, "s", List.of(VIOLATION, NOTE, VIOLATION), Map.of("b", 2.0, "a", 1.0));
    assertThat(verdict.violationCount()).isEqualTo(2);
    assertThat(verdict.metrics().firstKey()).isEqualTo("a");
    assertThatThrownBy(() -> verdict.metrics().put("c", 3.0))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void headerValidation() {
    HistoryHeader header =
        HistoryHeader.of("run", Map.of("q", QueueKind.STREAM), Map.of("version", "4.1"));
    assertThat(header.schemaVersion()).isEqualTo(HistoryHeader.CURRENT_SCHEMA_VERSION);
    assertThat(header.queues()).containsEntry("q", QueueKind.STREAM);
    assertThatThrownBy(() -> header.attributes().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> new HistoryHeader(2, "run", new TreeMap<>(), new TreeMap<>()))
        .hasMessageContaining("unsupported schemaVersion 2");
    assertThatThrownBy(() -> new HistoryHeader(1, "run", null, new TreeMap<>()))
        .hasMessageContaining("queues");
    assertThatThrownBy(() -> HistoryHeader.of("run", Map.of(" ", QueueKind.QUORUM), Map.of()))
        .hasMessageContaining("queues key");
    assertThatThrownBy(() -> HistoryHeader.of("run", Map.of(), Map.of("k", "v".repeat(4097))))
        .hasMessageContaining("attribute value");
    TreeMap<String, String> tooMany = new TreeMap<>();
    for (int i = 0; i <= 256; i++) {
      tooMany.put("k" + i, "v");
    }
    assertThatThrownBy(() -> new HistoryHeader(1, "run", new TreeMap<>(), tooMany))
        .hasMessageContaining("more than 256");
    tooMany.remove("k0");
    assertThat(new HistoryHeader(1, "run", new TreeMap<>(), tooMany).attributes()).hasSize(256);
  }
}
