package io.github.andassa.rmqverify.nemesis;

import java.util.List;

/**
 * A replayable schedule of faults.
 *
 * @param schemaVersion format version of {@code plan.json}
 * @param seed seed the plan was generated from
 * @param durationMillis length of the run the plan was made for
 * @param nodes nodes the plan targets
 * @param events faults, sorted by start and never overlapping
 */
public record FaultPlan(
    int schemaVersion,
    long seed,
    long durationMillis,
    List<String> nodes,
    List<FaultEvent> events) {

  /** Version written by this code. */
  public static final int CURRENT_SCHEMA_VERSION = 1;

  /** Validates ordering, overlap and targets. */
  public FaultPlan {
    if (schemaVersion != CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported plan schemaVersion " + schemaVersion);
    }
    if (durationMillis <= 0 || durationMillis > 24 * 3_600_000L) {
      throw new IllegalArgumentException("durationMillis out of range");
    }
    nodes = List.copyOf(nodes);
    events = List.copyOf(events);
    if (events.size() > 1000) {
      throw new IllegalArgumentException("a plan holds at most 1000 faults");
    }
    long previousEnd = 0;
    for (int i = 0; i < events.size(); i++) {
      FaultEvent event = events.get(i);
      if (event.index() != i) {
        throw new IllegalArgumentException("fault indices must be 0..n-1 in order");
      }
      if (event.startMillis() < previousEnd) {
        throw new IllegalArgumentException("fault " + i + " overlaps the previous one");
      }
      if (!nodes.containsAll(event.targets())) {
        throw new IllegalArgumentException(
            "fault " + i + " targets unknown nodes " + event.targets());
      }
      previousEnd = event.endMillis();
    }
  }

  /**
   * Returns a plan keeping only some events, renumbered; used by the minimiser.
   *
   * @param kept events to keep, in order
   * @return the reduced plan
   */
  public FaultPlan withEvents(List<FaultEvent> kept) {
    List<FaultEvent> renumbered = new java.util.ArrayList<>();
    for (FaultEvent e : kept) {
      renumbered.add(
          new FaultEvent(
              renumbered.size(),
              e.nemesis(),
              e.startMillis(),
              e.durationMillis(),
              e.targets(),
              e.params()));
    }
    return new FaultPlan(schemaVersion, seed, durationMillis, nodes, renumbered);
  }
}
