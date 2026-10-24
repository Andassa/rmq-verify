package io.github.andassa.rmqverify.nemesis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generates fault plans from a seed. The same inputs always give the same plan, byte for byte once
 * serialised (ADR-0010): faults are sequential, separated by quiet gaps, and the run starts and
 * ends with a fault-free period.
 *
 * @param registry available nemeses
 * @param nemeses names of the nemeses to draw from
 * @param faultDuration range of fault durations (min, max)
 * @param gap range of quiet periods between faults (min, max)
 * @param warmup fault-free period at the start of the run
 * @param cooldown fault-free period at the end of the run
 */
public record FaultPlanner(
    NemesisRegistry registry,
    List<String> nemeses,
    Range faultDuration,
    Range gap,
    Duration warmup,
    Duration cooldown) {

  /**
   * An inclusive range of durations.
   *
   * @param min lower bound
   * @param max upper bound
   */
  public record Range(Duration min, Duration max) {
    /** Validates the bounds. */
    public Range {
      if (min.isNegative() || min.isZero() || max.compareTo(min) < 0) {
        throw new IllegalArgumentException("invalid range " + min + ".." + max);
      }
    }

    long draw(SplittableRandom random) {
      return min.toMillis() == max.toMillis()
          ? min.toMillis()
          : random.nextLong(min.toMillis(), max.toMillis() + 1);
    }
  }

  /** Checks that every requested nemesis exists. */
  public FaultPlanner {
    nemeses = List.copyOf(nemeses);
    for (String name : nemeses) {
      if (registry.byName(name).isEmpty()) {
        throw new IllegalArgumentException(
            "unknown nemesis " + name + "; available: " + registry.names());
      }
    }
  }

  /**
   * Plans the faults of one run.
   *
   * @param seed seed of the plan
   * @param runDuration length of the run
   * @param nodes cluster nodes
   * @return the plan; empty when no nemesis is requested
   */
  public FaultPlan plan(long seed, Duration runDuration, List<String> nodes) {
    SplittableRandom random = new SplittableRandom(seed);
    List<FaultEvent> events = new ArrayList<>();
    long cursor = warmup.toMillis();
    long end = runDuration.toMillis() - cooldown.toMillis();
    while (!nemeses.isEmpty()) {
      long duration = faultDuration.draw(random);
      if (cursor + duration > end) {
        break;
      }
      String name = nemeses.get(random.nextInt(nemeses.size()));
      Nemesis.Choice choice = registry.byName(name).orElseThrow().choose(random, nodes);
      events.add(
          new FaultEvent(events.size(), name, cursor, duration, choice.targets(), choice.params()));
      cursor += duration + gap.draw(random);
    }
    return new FaultPlan(
        FaultPlan.CURRENT_SCHEMA_VERSION, seed, runDuration.toMillis(), nodes, events);
  }
}
