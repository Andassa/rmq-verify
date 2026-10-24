package io.github.andassa.rmqverify.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Result of one checker on one history.
 *
 * @param checker name of the checker
 * @param status conclusion; {@code INVALID} if and only if a violation is present
 * @param summary one-line human-readable summary
 * @param anomalies findings, violations and notes
 * @param metrics named measurements (counts, rates, durations in milliseconds)
 */
public record Verdict(
    String checker,
    VerdictStatus status,
    String summary,
    List<Anomaly> anomalies,
    SortedMap<String, Double> metrics) {

  /** Validates the consistency between status and anomalies, and copies the collections. */
  public Verdict {
    Checks.name(checker, "checker");
    Checks.notNull(status, "status");
    Checks.text(summary, "summary");
    anomalies = List.copyOf(Checks.notNull(anomalies, "anomalies"));
    metrics = Collections.unmodifiableSortedMap(new TreeMap<>(Checks.notNull(metrics, "metrics")));
    boolean violated = anomalies.stream().anyMatch(Anomaly::isViolation);
    if (violated != (status == VerdictStatus.INVALID)) {
      throw new IllegalArgumentException(
          checker + ": status " + status + " is inconsistent with violations=" + violated);
    }
  }

  /**
   * Builds a verdict whose status follows from the anomalies: {@code INVALID} if any violation,
   * otherwise {@code VALID} when the history was conclusive and {@code UNKNOWN} when it was not.
   *
   * @param checker name of the checker
   * @param conclusive whether the history allowed a conclusion
   * @param summary one-line summary
   * @param anomalies findings
   * @param metrics measurements
   * @return the verdict
   */
  public static Verdict of(
      String checker,
      boolean conclusive,
      String summary,
      List<Anomaly> anomalies,
      Map<String, Double> metrics) {
    VerdictStatus status;
    if (anomalies.stream().anyMatch(Anomaly::isViolation)) {
      status = VerdictStatus.INVALID;
    } else {
      status = conclusive ? VerdictStatus.VALID : VerdictStatus.UNKNOWN;
    }
    return new Verdict(checker, status, summary, anomalies, new TreeMap<>(metrics));
  }

  /**
   * Counts the violations.
   *
   * @return the number of anomalies of severity {@link Severity#VIOLATION}
   */
  public long violationCount() {
    return anomalies.stream().filter(Anomaly::isViolation).count();
  }
}
