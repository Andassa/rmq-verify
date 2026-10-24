package io.github.andassa.rmqverify.nemesis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

class PlanProperties {

  private static final NemesisRegistry REGISTRY = NemesisRegistry.load();
  private static final List<String> NODES = List.of("rabbit1", "rabbit2", "rabbit3");

  private static FaultPlanner planner(List<String> nemeses) {
    return new FaultPlanner(
        REGISTRY,
        nemeses,
        new FaultPlanner.Range(Duration.ofSeconds(5), Duration.ofSeconds(20)),
        new FaultPlanner.Range(Duration.ofSeconds(1), Duration.ofSeconds(10)),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5));
  }

  @Property(tries = 200)
  void sameSeedGivesTheSamePlanBytes(
      @ForAll long seed,
      @ForAll @IntRange(min = 30, max = 900) int seconds,
      @ForAll @Size(min = 1, max = 9) List<@IntRange(min = 0, max = 8) Integer> picks) {
    List<String> nemeses = picks.stream().map(i -> REGISTRY.names().get(i)).distinct().toList();
    FaultPlanCodec codec = new FaultPlanCodec(REGISTRY);
    String first = codec.write(planner(nemeses).plan(seed, Duration.ofSeconds(seconds), NODES));
    String second = codec.write(planner(nemeses).plan(seed, Duration.ofSeconds(seconds), NODES));
    assertThat(first).isEqualTo(second);
  }

  @Property(tries = 200)
  void plansAreSequentialBoundedAndRoundTrip(
      @ForAll long seed, @ForAll @IntRange(min = 30, max = 900) int seconds)
      throws InvalidPlanException {
    FaultPlan plan = planner(REGISTRY.names()).plan(seed, Duration.ofSeconds(seconds), NODES);
    long previousEnd = 0;
    for (FaultEvent e : plan.events()) {
      assertThat(e.startMillis()).isGreaterThanOrEqualTo(Math.max(5000, previousEnd));
      assertThat(e.endMillis()).isLessThanOrEqualTo(seconds * 1000L - 5000);
      assertThat(e.durationMillis()).isBetween(5000L, 20_000L);
      assertThat(NODES).containsAll(e.targets());
      previousEnd = e.endMillis();
    }
    FaultPlanCodec codec = new FaultPlanCodec(REGISTRY);
    assertThat(codec.read(codec.write(plan))).isEqualTo(plan);
  }
}
