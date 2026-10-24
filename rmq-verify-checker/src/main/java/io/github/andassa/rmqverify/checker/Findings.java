package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.Anomaly;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.Severity;
import io.github.andassa.rmqverify.model.Verdict;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Accumulates the anomalies and metrics of one checker run, then builds its verdict. */
public final class Findings {

  private final String checker;
  private final List<Anomaly> anomalies = new ArrayList<>();
  private final Map<String, Double> metrics = new TreeMap<>();

  /**
   * Starts collecting findings.
   *
   * @param checker name of the checker
   */
  public Findings(String checker) {
    this.checker = checker;
  }

  /**
   * Records a violation of a guarantee.
   *
   * @param kind UPPER_SNAKE_CASE identifier
   * @param description human-readable explanation
   * @param evidence operations that prove it; must not be empty
   */
  public void violation(String kind, String description, Collection<Operation> evidence) {
    anomalies.add(new Anomaly(kind, Severity.VIOLATION, description, ids(evidence)));
  }

  /**
   * Records a finding that does not break a guarantee.
   *
   * @param kind UPPER_SNAKE_CASE identifier
   * @param description human-readable explanation
   * @param evidence related operations, possibly none
   */
  public void note(String kind, String description, Collection<Operation> evidence) {
    anomalies.add(new Anomaly(kind, Severity.NOTE, description, ids(evidence)));
  }

  /**
   * Records a measurement.
   *
   * @param name metric name
   * @param value value
   */
  public void metric(String name, double value) {
    metrics.put(name, value);
  }

  /**
   * Records a count.
   *
   * @param name metric name
   * @param count value
   */
  public void metric(String name, long count) {
    metrics.put(name, (double) count);
  }

  /**
   * Returns a recorded metric.
   *
   * @param name metric name
   * @return its value, 0 if absent
   */
  public double metricValue(String name) {
    return metrics.getOrDefault(name, 0.0);
  }

  /**
   * Counts the findings of one kind.
   *
   * @param kind anomaly kind
   * @return the number of anomalies of that kind
   */
  public long count(String kind) {
    return anomalies.stream().filter(a -> a.kind().equals(kind)).count();
  }

  /**
   * Builds the verdict: INVALID with any violation, otherwise VALID if conclusive, else UNKNOWN.
   *
   * @param conclusive whether the history allowed a conclusion
   * @param summary one-line summary
   * @return the verdict
   */
  public Verdict verdict(boolean conclusive, String summary) {
    return Verdict.of(checker, conclusive, summary, anomalies, metrics);
  }

  private static List<Long> ids(Collection<Operation> evidence) {
    return evidence.stream().map(Operation::id).sorted().toList();
  }
}
