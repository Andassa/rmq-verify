package io.github.andassa.rmqverify.report;

import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.VerdictStatus;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Everything a report says about one run: configuration, faults and verdicts. Serialised as {@code
 * report.json}; the matrix and {@code RESULTS.md} are computed from these files only.
 *
 * @param runId run identifier
 * @param scenario scenario name, groups repetitions in the matrix
 * @param repetition repetition number within the scenario, from 1
 * @param rabbitmqVersion version reported by the broker
 * @param queueKind queue type exercised
 * @param seed seed of the workload and of the plan
 * @param startedAt ISO-8601 start time
 * @param durationMillis planned run duration
 * @param settings remaining configuration, as strings
 * @param faults the fault plan
 * @param verdicts one verdict per checker
 */
public record RunSummary(
    String runId,
    String scenario,
    int repetition,
    String rabbitmqVersion,
    String queueKind,
    long seed,
    String startedAt,
    long durationMillis,
    SortedMap<String, String> settings,
    List<FaultSummary> faults,
    List<Verdict> verdicts) {

  /** Copies the collections. */
  public RunSummary {
    settings = Collections.unmodifiableSortedMap(new TreeMap<>(settings));
    faults = List.copyOf(faults);
    verdicts = List.copyOf(verdicts);
  }

  /**
   * Returns the overall status: {@code INVALID} if any checker failed, {@code UNKNOWN} if any was
   * inconclusive, {@code VALID} otherwise.
   *
   * @return the overall status
   */
  public VerdictStatus overall() {
    if (verdicts.stream().anyMatch(v -> v.status() == VerdictStatus.INVALID)) {
      return VerdictStatus.INVALID;
    }
    return verdicts.stream().anyMatch(v -> v.status() == VerdictStatus.UNKNOWN)
        ? VerdictStatus.UNKNOWN
        : VerdictStatus.VALID;
  }
}
