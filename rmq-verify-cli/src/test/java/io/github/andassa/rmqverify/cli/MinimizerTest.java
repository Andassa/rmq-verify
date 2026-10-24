package io.github.andassa.rmqverify.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.FaultPlan;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MinimizerTest {

  private static final List<String> NODES = List.of("rabbit1", "rabbit2", "rabbit3");

  private static FaultPlan plan(String... nemeses) {
    List<FaultEvent> events = new ArrayList<>();
    for (int i = 0; i < nemeses.length; i++) {
      events.add(
          new FaultEvent(
              i, nemeses[i], 5_000 + 20_000L * i, 10_000, List.of("rabbit1"), new TreeMap<>()));
    }
    return new FaultPlan(1, 42, 120_000, NODES, events);
  }

  @Test
  void keepsOnlyTheFaultsAndLoadThatReproduce() throws Exception {
    List<String> asked = new ArrayList<>();
    Minimizer.Oracle oracle =
        (candidate, publishers, duration) -> {
          asked.add(candidate.events().size() + "/" + publishers + "/" + duration.toSeconds());
          return candidate.events().stream().anyMatch(e -> e.nemesis().equals("kill-node"))
              && publishers >= 2;
        };
    Minimizer.Result result =
        new Minimizer(oracle)
            .minimize(
                plan("network-latency", "packet-loss", "kill-node", "pause-node"),
                8,
                Duration.ofMinutes(2));
    assertThat(result.plan().events())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.nemesis()).isEqualTo("kill-node");
              assertThat(e.index()).isZero();
              assertThat(e.startMillis()).isEqualTo(45_000);
            });
    assertThat(result.publishers()).isEqualTo(2);
    assertThat(result.duration()).isEqualTo(Duration.ofSeconds(65));
    assertThat(result.plan().durationMillis()).isEqualTo(65_000);
    assertThat(result.plan().seed()).isEqualTo(42);
    assertThat(result.candidates()).isEqualTo(asked.size());
    assertThat(Minimizer.describe(result)).containsExactly("#0 kill-node targets=[rabbit1]");
  }

  @Test
  void aFaultIndependentFailureNeedsNoFault() throws Exception {
    Minimizer.Result result =
        new Minimizer((candidate, publishers, duration) -> true)
            .minimize(plan("kill-node", "pause-node"), 1, Duration.ofMinutes(1));
    assertThat(result.plan().events()).isEmpty();
    assertThat(result.publishers()).isEqualTo(1);
    assertThat(result.duration()).isEqualTo(Duration.ofSeconds(20));
  }

  @Test
  void keepsTheOriginalDurationWhenShorterRunsDoNotFail() throws Exception {
    Minimizer.Result result =
        new Minimizer((candidate, publishers, duration) -> duration.toSeconds() >= 100)
            .minimize(plan("kill-node"), 1, Duration.ofSeconds(100));
    assertThat(result.duration()).isEqualTo(Duration.ofSeconds(100));
  }

  @Test
  void oracleErrorsAbortTheMinimisation() {
    Minimizer minimizer =
        new Minimizer(
            (candidate, publishers, duration) -> {
              throw new IOException("cluster gone");
            });
    assertThatThrownBy(() -> minimizer.minimize(plan("kill-node"), 1, Duration.ofMinutes(1)))
        .isInstanceOf(IOException.class)
        .hasMessage("cluster gone");
  }

  @Test
  void shortestCoveringRunEndsTenSecondsAfterTheLastFault() {
    assertThat(Minimizer.shortestCovering(plan())).isEqualTo(Duration.ofSeconds(20));
    assertThat(Minimizer.shortestCovering(plan("kill-node", "kill-node")))
        .isEqualTo(Duration.ofSeconds(45));
  }
}
