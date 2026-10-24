package io.github.andassa.rmqverify.nemesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanValidationTest {

  private static final NemesisRegistry REGISTRY = NemesisRegistry.load();
  private static final FaultPlanCodec CODEC = new FaultPlanCodec(REGISTRY);
  private static final List<String> NODES = List.of("rabbit1", "rabbit2", "rabbit3");

  private static FaultEvent event(int index, long start, long duration) {
    return new FaultEvent(index, "kill-node", start, duration, List.of("rabbit1"), new TreeMap<>());
  }

  @Test
  void planInvariants() {
    assertThatThrownBy(() -> new FaultPlan(2, 1, 1000, NODES, List.of()))
        .hasMessageContaining("schemaVersion");
    assertThatThrownBy(() -> new FaultPlan(1, 1, 0, NODES, List.of()))
        .hasMessageContaining("durationMillis");
    assertThatThrownBy(() -> new FaultPlan(1, 1, 1000, NODES, List.of(event(1, 0, 10))))
        .hasMessageContaining("indices");
    assertThatThrownBy(
            () -> new FaultPlan(1, 1, 1000, NODES, List.of(event(0, 0, 100), event(1, 50, 10))))
        .hasMessageContaining("overlaps");
    assertThat(
            new FaultPlan(1, 1, 1000, NODES, List.of(event(0, 0, 100), event(1, 100, 10))).events())
        .hasSize(2);
    assertThatThrownBy(
            () -> new FaultPlan(1, 1, 1000, List.of("rabbit2"), List.of(event(0, 0, 10))))
        .hasMessageContaining("unknown nodes");
  }

  @Test
  void eventInvariants() {
    TreeMap<String, String> none = new TreeMap<>();
    assertThatThrownBy(() -> new FaultEvent(0, "Kill Node", 0, 1, List.of(), none))
        .hasMessageContaining("nemesis name");
    assertThatThrownBy(() -> new FaultEvent(-1, "k", 0, 1, List.of(), none))
        .hasMessageContaining("timing");
    assertThatThrownBy(() -> new FaultEvent(0, "k", 0, 0, List.of(), none))
        .hasMessageContaining("timing");
    assertThatThrownBy(() -> new FaultEvent(0, "k", -1, 1, List.of(), none))
        .hasMessageContaining("timing");
    assertThatThrownBy(() -> new FaultEvent(0, "k", 0, 3_600_001, List.of(), none))
        .hasMessageContaining("timing");
    TreeMap<String, String> bad = new TreeMap<>();
    bad.put("not valid!", "x");
    assertThatThrownBy(() -> new FaultEvent(0, "k", 0, 1, List.of(), bad))
        .hasMessageContaining("invalid param");
    TreeMap<String, String> longValue = new TreeMap<>();
    longValue.put("p", "x".repeat(65));
    assertThatThrownBy(() -> new FaultEvent(0, "k", 0, 1, List.of(), longValue))
        .hasMessageContaining("invalid param");
    FaultEvent ok =
        new FaultEvent(0, "k", 10, 5, List.of("a"), new TreeMap<>(java.util.Map.of("n", "3")));
    assertThat(ok.endMillis()).isEqualTo(15);
    assertThat(ok.intParam("n")).isEqualTo(3);
    assertThat(ok.describe()).isEqualTo("targets=[a] n=3");
    assertThatThrownBy(() -> ok.intParam("missing"))
        .hasMessageContaining("lacks parameter missing");
  }

  @Test
  void codecRejectsHostilePlans(@TempDir Path dir) throws Exception {
    String valid = CODEC.write(new FaultPlan(1, 1, 60_000, NODES, List.of(event(0, 0, 10))));
    assertThat(CODEC.read(valid).events()).hasSize(1);
    assertThatThrownBy(() -> CODEC.read(valid.replace("\"seed\"", "\"extra\" : 1, \"seed\"")))
        .isInstanceOf(InvalidPlanException.class)
        .hasMessageContaining("malformed plan");
    assertThatThrownBy(() -> CODEC.read(valid.replace("kill-node", "rm-rf")))
        .isInstanceOf(InvalidPlanException.class)
        .hasMessageContaining("unknown nemesis rm-rf");
    assertThatThrownBy(() -> CODEC.read("{\"@class\":\"java.lang.Runtime\"}"))
        .isInstanceOf(InvalidPlanException.class);
    assertThatThrownBy(() -> CODEC.read(valid + "{}")).isInstanceOf(InvalidPlanException.class);
    assertThatThrownBy(() -> CODEC.read(" ".repeat((int) FaultPlanCodec.MAX_BYTES + 1)))
        .hasMessageContaining("larger than");
    Path file = dir.resolve("plan.json");
    CODEC.write(CODEC.read(valid), file);
    assertThat(CODEC.read(file)).isEqualTo(CODEC.read(valid));
    Files.writeString(file, " ".repeat((int) FaultPlanCodec.MAX_BYTES + 1));
    assertThatThrownBy(() -> CODEC.read(file)).hasMessageContaining("larger than");
  }

  @Test
  void plannerRejectsUnknownNemesesAndBadRanges() {
    FaultPlanner.Range range = new FaultPlanner.Range(Duration.ofSeconds(1), Duration.ofSeconds(1));
    assertThatThrownBy(
            () ->
                new FaultPlanner(
                    REGISTRY, List.of("meteor"), range, range, Duration.ZERO, Duration.ZERO))
        .hasMessageContaining("unknown nemesis meteor");
    assertThatThrownBy(() -> new FaultPlanner.Range(Duration.ZERO, Duration.ofSeconds(1)))
        .hasMessageContaining("range");
    assertThatThrownBy(() -> new FaultPlanner.Range(Duration.ofSeconds(2), Duration.ofSeconds(1)))
        .hasMessageContaining("range");
    FaultPlanner none =
        new FaultPlanner(REGISTRY, List.of(), range, range, Duration.ZERO, Duration.ZERO);
    assertThat(none.plan(1, Duration.ofMinutes(5), NODES).events()).isEmpty();
  }

  @Test
  void withEventsRenumbers() {
    FaultPlan plan =
        new FaultPlan(
            1, 1, 60_000, NODES, List.of(event(0, 0, 10), event(1, 20, 10), event(2, 40, 10)));
    FaultPlan reduced = plan.withEvents(List.of(plan.events().get(2)));
    assertThat(reduced.events())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.index()).isZero();
              assertThat(e.startMillis()).isEqualTo(40);
            });
    assertThat(reduced.seed()).isEqualTo(plan.seed());
  }
}
