package io.github.andassa.rmqverify.nemesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NemesesTest {

  private static final NemesisRegistry REGISTRY = NemesisRegistry.load();
  private static final List<String> NODES = List.of("rabbit1", "rabbit2", "rabbit3");

  @Test
  void registryHoldsTheNineNemeses() {
    assertThat(REGISTRY.names())
        .containsExactly(
            "client-toxic",
            "kill-node",
            "network-latency",
            "packet-loss",
            "partition-bridge",
            "partition-halves",
            "partition-isolate",
            "pause-node",
            "rolling-restart");
    assertThat(REGISTRY.byName("nope")).isEmpty();
    Nemesis kill = REGISTRY.byName("kill-node").orElseThrow();
    assertThatThrownBy(() -> NemesisRegistry.of(List.of(kill, kill)))
        .hasMessageContaining("two nemeses");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "kill-node|kill rabbit2|start rabbit2",
        "pause-node|pause rabbit2|unpause rabbit2",
        "partition-isolate|cut [rabbit2-rabbit1, rabbit2-rabbit3]|healNetwork",
        "network-latency|netem rabbit2 [delay, 300ms, 75ms]|clearNetem rabbit2",
        "packet-loss|netem rabbit2 [loss, 12.00%]|clearNetem rabbit2",
      })
  void singleNodeNemeses(String name, String injected, String healed) throws Exception {
    Nemesis nemesis = REGISTRY.byName(name).orElseThrow();
    TreeMap<String, String> params = new TreeMap<>();
    params.put("delayMs", "300");
    params.put("jitterMs", "75");
    params.put("lossPercent", "12");
    FaultEvent event = new FaultEvent(0, name, 0, 1000, List.of("rabbit2"), params);
    RecordingCluster cluster = new RecordingCluster();
    nemesis.inject(event, cluster);
    nemesis.heal(event, cluster);
    assertThat(cluster.calls).containsExactly(injected, healed);
  }

  @Test
  void choicesAreSeededAndWithinTheCluster() {
    for (String name : REGISTRY.names()) {
      Nemesis nemesis = REGISTRY.byName(name).orElseThrow();
      Nemesis.Choice a = nemesis.choose(new SplittableRandom(9), NODES);
      Nemesis.Choice b = nemesis.choose(new SplittableRandom(9), NODES);
      assertThat(a).as(name).isEqualTo(b);
      assertThat(NODES).containsAll(a.targets());
      assertThat(a.targets()).isNotEmpty();
    }
  }

  @Test
  void halvesCutsTheMinorityFromTheMajority() throws Exception {
    Nemesis halves = REGISTRY.byName("partition-halves").orElseThrow();
    Nemesis.Choice choice = halves.choose(new SplittableRandom(1), NODES);
    assertThat(choice.targets()).hasSize(1);
    RecordingCluster cluster = new RecordingCluster();
    FaultEvent event =
        new FaultEvent(0, "partition-halves", 0, 1, choice.targets(), choice.params());
    halves.inject(event, cluster);
    halves.heal(event, cluster);
    String minority = choice.targets().get(0);
    assertThat(cluster.calls.get(0))
        .startsWith("cut [")
        .contains(minority + "-")
        .doesNotContain("-" + minority);
    assertThat(cluster.calls.get(1)).isEqualTo("healNetwork");
  }

  @Test
  void bridgeKeepsOneNodeConnectedToBothSides() throws Exception {
    Nemesis bridge = REGISTRY.byName("partition-bridge").orElseThrow();
    Nemesis.Choice choice = bridge.choose(new SplittableRandom(4), NODES);
    assertThat(choice.targets()).containsExactlyInAnyOrderElementsOf(NODES);
    assertThat(choice.params()).containsEntry("left", "1");
    RecordingCluster cluster = new RecordingCluster();
    FaultEvent event =
        new FaultEvent(0, "partition-bridge", 0, 1, choice.targets(), choice.params());
    bridge.inject(event, cluster);
    bridge.heal(event, cluster);
    List<String> t = choice.targets();
    assertThat(cluster.calls)
        .containsExactly("cut [" + t.get(1) + "-" + t.get(2) + "]", "healNetwork");
    assertThatThrownBy(() -> bridge.choose(new SplittableRandom(1), List.of("a", "b")))
        .hasMessageContaining("at least three");
  }

  @Test
  void clientToxicCutsOrDelays() throws Exception {
    Nemesis toxic = REGISTRY.byName("client-toxic").orElseThrow();
    RecordingCluster cluster = new RecordingCluster();
    TreeMap<String, String> cut = new TreeMap<>();
    cut.put("mode", "cut");
    cut.put("latencyMs", "1500");
    TreeMap<String, String> latency = new TreeMap<>(cut);
    latency.put("mode", "latency");
    toxic.inject(new FaultEvent(0, "client-toxic", 0, 1, List.of("rabbit1"), cut), cluster);
    toxic.inject(new FaultEvent(0, "client-toxic", 0, 1, List.of("rabbit1"), latency), cluster);
    toxic.heal(new FaultEvent(0, "client-toxic", 0, 1, List.of("rabbit1"), cut), cluster);
    assertThat(cluster.calls)
        .containsExactly("cutClient rabbit1", "clientLatency rabbit1 1500", "resetClient");
  }

  @Test
  void rollingRestartWaitsForHealthBetweenNodes() throws Exception {
    Nemesis rolling = REGISTRY.byName("rolling-restart").orElseThrow();
    RecordingCluster cluster = new RecordingCluster();
    FaultEvent event =
        new FaultEvent(0, "rolling-restart", 0, 1, List.of("rabbit3", "rabbit1"), new TreeMap<>());
    rolling.inject(event, cluster);
    rolling.heal(event, cluster);
    assertThat(cluster.calls)
        .containsExactly(
            "stop rabbit3",
            "start rabbit3",
            "waitHealthy",
            "stop rabbit1",
            "start rabbit1",
            "waitHealthy",
            "start rabbit3",
            "start rabbit1");
  }
}
