package io.github.andassa.rmqverify.report;

import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.VerdictStatus;
import java.util.List;

/**
 * Aggregate of the repetitions of one scenario on one RabbitMQ version.
 *
 * @param scenario scenario name
 * @param version RabbitMQ version
 * @param queueKind queue type of the scenario
 * @param runs number of repetitions
 * @param invalidRuns repetitions with at least one INVALID verdict
 * @param unknownRuns repetitions without violation but with an UNKNOWN verdict
 * @param confirmed confirmed publishes, all repetitions
 * @param indeterminate indeterminate publishes, all repetitions
 * @param lostConfirmed confirmed messages never delivered
 * @param missingAfterRecovery confirmed before a fault and absent after it
 * @param unexpectedDuplicates duplicates without the redelivered flag or at two offsets
 * @param orderViolations FIFO inversions and gaps
 * @param unexpectedMessages corrupt, unknown or mismatched deliveries
 * @param maxRecoveryMs longest recovery after a heal
 * @param minSuccessRate lowest share of confirmed publishes in a repetition
 * @param runIds run identifiers, for traceability
 */
public record MatrixCell(
    String scenario,
    String version,
    String queueKind,
    int runs,
    int invalidRuns,
    int unknownRuns,
    long confirmed,
    long indeterminate,
    long lostConfirmed,
    long missingAfterRecovery,
    long unexpectedDuplicates,
    long orderViolations,
    long unexpectedMessages,
    double maxRecoveryMs,
    double minSuccessRate,
    List<String> runIds) {

  /** Copies the run identifiers. */
  public MatrixCell {
    runIds = List.copyOf(runIds);
  }

  /**
   * Aggregates the repetitions of one scenario and version.
   *
   * @param runs the summaries, all of the same scenario and version
   * @return the cell
   */
  public static MatrixCell of(List<RunSummary> runs) {
    RunSummary first = runs.get(0);
    int invalid = 0;
    int unknown = 0;
    double maxRecovery = 0;
    double minSuccess = 1;
    long[] sums = new long[7];
    for (RunSummary run : runs) {
      invalid += run.overall() == VerdictStatus.INVALID ? 1 : 0;
      unknown += run.overall() == VerdictStatus.UNKNOWN ? 1 : 0;
      sums[0] += (long) metric(run, "LostMessages", "confirmed");
      sums[1] += (long) metric(run, "LostMessages", "indeterminate");
      sums[2] += (long) metric(run, "LostMessages", "lostConfirmed");
      sums[3] += (long) metric(run, "ConfirmedButMissingAfterRecovery", "missingAfterRecovery");
      sums[4] += (long) metric(run, "DuplicateDeliveries", "unexpectedDuplicates");
      sums[5] +=
          (long) (metric(run, "FifoOrdering", "inversions") + metric(run, "FifoOrdering", "gaps"));
      sums[6] +=
          (long)
              (metric(run, "UnexpectedMessages", "corrupt")
                  + metric(run, "UnexpectedMessages", "unknown")
                  + metric(run, "UnexpectedMessages", "mismatched"));
      maxRecovery = Math.max(maxRecovery, metric(run, "Availability", "maxRecoveryMs"));
      minSuccess = Math.min(minSuccess, metric(run, "Availability", "successRate"));
    }
    return new MatrixCell(
        first.scenario(),
        first.rabbitmqVersion(),
        first.queueKind(),
        runs.size(),
        invalid,
        unknown,
        sums[0],
        sums[1],
        sums[2],
        sums[3],
        sums[4],
        sums[5],
        sums[6],
        maxRecovery,
        minSuccess,
        runs.stream().map(RunSummary::runId).toList());
  }

  private static double metric(RunSummary run, String checker, String name) {
    return run.verdicts().stream()
        .filter(v -> v.checker().equals(checker))
        .map(Verdict::metrics)
        .map(m -> m.getOrDefault(name, 0.0))
        .findFirst()
        .orElse(0.0);
  }
}
