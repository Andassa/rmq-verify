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

/** Accumulates the anomalies and metrics of one checker run. */
final class Findings {

  private final String checker;
  private final List<Anomaly> anomalies = new ArrayList<>();
  private final Map<String, Double> metrics = new TreeMap<>();

  Findings(String checker) {
    this.checker = checker;
  }

  void violation(String kind, String description, Collection<Operation> evidence) {
    anomalies.add(new Anomaly(kind, Severity.VIOLATION, description, ids(evidence)));
  }

  void note(String kind, String description, Collection<Operation> evidence) {
    anomalies.add(new Anomaly(kind, Severity.NOTE, description, ids(evidence)));
  }

  void metric(String name, double value) {
    metrics.put(name, value);
  }

  void metric(String name, long count) {
    metrics.put(name, (double) count);
  }

  double metricValue(String name) {
    return metrics.getOrDefault(name, 0.0);
  }

  long count(String kind) {
    return anomalies.stream().filter(a -> a.kind().equals(kind)).count();
  }

  Verdict verdict(boolean conclusive, String summary) {
    return Verdict.of(checker, conclusive, summary, anomalies, metrics);
  }

  private static List<Long> ids(Collection<Operation> evidence) {
    return evidence.stream().map(Operation::id).sorted().toList();
  }
}
