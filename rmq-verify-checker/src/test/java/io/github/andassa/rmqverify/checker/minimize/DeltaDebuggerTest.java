package io.github.andassa.rmqverify.checker.minimize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.UniqueElements;
import org.junit.jupiter.api.Test;

class DeltaDebuggerTest {

  @Test
  void isolatesASingleCulprit() {
    List<Integer> input = IntStream.range(0, 100).boxed().toList();
    Minimization<Integer> result = DeltaDebugger.minimize(input, c -> c.contains(42));
    assertThat(result.result()).containsExactly(42);
    assertThat(result.tests()).isLessThan(30);
  }

  @Test
  void keepsInteractingElementsInOrder() {
    List<String> input = List.of("a", "b", "c", "d", "e", "f", "g", "h");
    Minimization<String> result =
        DeltaDebugger.minimize(input, c -> c.contains("c") && c.contains("g"));
    assertThat(result.result()).containsExactly("c", "g");
  }

  @Test
  void returnsEmptyWhenTheFailureNeedsNothing() {
    Minimization<Integer> result = DeltaDebugger.minimize(List.of(1, 2, 3), c -> true);
    assertThat(result.result()).isEmpty();
    assertThat(result.tests()).isEqualTo(2);
  }

  @Test
  void rejectsAnInputThatDoesNotFail() {
    assertThatThrownBy(() -> DeltaDebugger.minimize(List.of(1, 2), c -> false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not reproduce");
  }

  @Test
  void memoisesOracleAnswers() {
    AtomicInteger calls = new AtomicInteger();
    Minimization<Integer> result =
        DeltaDebugger.minimize(
            IntStream.range(0, 16).boxed().toList(),
            c -> {
              calls.incrementAndGet();
              return c.contains(3) && c.contains(11);
            });
    assertThat(calls.get()).isEqualTo(result.tests());
    assertThat(result.result()).containsExactly(3, 11);
  }

  @Test
  void splitCoversEveryElementInBalancedChunks() {
    List<Integer> items = IntStream.range(0, 10).boxed().toList();
    List<List<Integer>> chunks = DeltaDebugger.split(items, 3);
    assertThat(chunks).extracting(List::size).containsExactly(3, 3, 4);
    assertThat(chunks.stream().flatMap(List::stream).toList()).isEqualTo(items);
  }

  @Property(tries = 300)
  void resultFailsIsASubsequenceAndIsOneMinimal(
      @ForAll @Size(min = 1, max = 40) @UniqueElements
          List<@IntRange(min = 0, max = 200) Integer> input,
      @ForAll @Size(min = 1, max = 3) Set<@IntRange(min = 0, max = 39) Integer> culpritPositions) {
    Set<Integer> culprits = new HashSet<>();
    for (int position : culpritPositions) {
      culprits.add(input.get(position % input.size()));
    }
    Predicate<List<Integer>> fails = c -> c.containsAll(culprits);
    List<Integer> result = DeltaDebugger.minimize(input, fails).result();
    assertThat(fails.test(result)).isTrue();
    assertThat(input).containsSubsequence(result);
    for (int i = 0; i < result.size(); i++) {
      List<Integer> smaller = new ArrayList<>(result.subList(0, i));
      smaller.addAll(result.subList(i + 1, result.size()));
      assertThat(fails.test(smaller)).as("removing %s still fails", result.get(i)).isFalse();
    }
    assertThat(new HashSet<>(result)).isEqualTo(culprits);
  }
}
