package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.nemesis.FaultPlan;
import io.github.andassa.rmqverify.nemesis.FaultPlanner;
import io.github.andassa.rmqverify.nemesis.NemesisRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Option;

/** Options of the seeded fault planner. */
final class FaultOptions {

  @Option(
      names = "--nemesis",
      split = ",",
      description = "Comma-separated nemeses; none means a fault-free run.")
  List<String> nemeses = new ArrayList<>();

  @Option(
      names = "--fault-min",
      defaultValue = "10",
      description = "Shortest fault in s (default: ${DEFAULT-VALUE}).")
  long faultMinSeconds;

  @Option(
      names = "--fault-max",
      defaultValue = "20",
      description = "Longest fault in s (default: ${DEFAULT-VALUE}).")
  long faultMaxSeconds;

  @Option(
      names = "--gap-min",
      defaultValue = "10",
      description = "Shortest quiet gap in s (default: ${DEFAULT-VALUE}).")
  long gapMinSeconds;

  @Option(
      names = "--gap-max",
      defaultValue = "20",
      description = "Longest quiet gap in s (default: ${DEFAULT-VALUE}).")
  long gapMaxSeconds;

  FaultPlan plan(NemesisRegistry registry, long seed, Duration runDuration, List<String> nodes) {
    FaultPlanner planner =
        new FaultPlanner(
            registry,
            nemeses,
            new FaultPlanner.Range(
                Duration.ofSeconds(faultMinSeconds), Duration.ofSeconds(faultMaxSeconds)),
            new FaultPlanner.Range(
                Duration.ofSeconds(gapMinSeconds), Duration.ofSeconds(gapMaxSeconds)),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5));
    return planner.plan(seed, runDuration, nodes);
  }
}
