package io.github.andassa.rmqverify.report;

import java.util.List;

/**
 * A planned fault, as shown in reports.
 *
 * @param index position in the plan
 * @param nemesis nemesis name
 * @param startMillis offset from the start of the run
 * @param durationMillis planned duration
 * @param targets affected nodes
 * @param detail parameters
 */
public record FaultSummary(
    int index,
    String nemesis,
    long startMillis,
    long durationMillis,
    List<String> targets,
    String detail) {

  /** Copies the targets. */
  public FaultSummary {
    targets = List.copyOf(targets);
  }
}
