package io.github.andassa.rmqverify.checker.minimize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Zeller and Hildebrandt's {@code ddmin}: reduces a failing input to a subsequence that still fails
 * and from which no single element can be removed without the failure disappearing (1-minimal).
 * Oracle answers are memoised because each one may cost several real runs against a cluster.
 */
public final class DeltaDebugger {

  private DeltaDebugger() {}

  /**
   * Minimises a failing input.
   *
   * @param input the failing input, order is preserved in every candidate
   * @param fails oracle returning {@code true} when a candidate still exhibits the failure
   * @param <T> element type
   * @return the minimal failing subsequence and the number of oracle calls
   * @throws IllegalArgumentException if {@code input} itself does not fail
   */
  public static <T> Minimization<T> minimize(List<T> input, Predicate<List<T>> fails) {
    Oracle<T> oracle = new Oracle<>(input, fails);
    List<Integer> current = new ArrayList<>();
    for (int i = 0; i < input.size(); i++) {
      current.add(i);
    }
    if (!oracle.test(current)) {
      throw new IllegalArgumentException("the input does not reproduce the failure");
    }
    if (oracle.test(List.of())) {
      return new Minimization<>(List.of(), oracle.calls());
    }
    int granularity = 2;
    while (current.size() >= 2) {
      List<List<Integer>> chunks = split(current, granularity);
      List<Integer> reduced = firstFailing(chunks, oracle);
      if (reduced != null) {
        current = reduced;
        granularity = 2;
        continue;
      }
      reduced = firstFailing(complements(current, chunks), oracle);
      if (reduced != null) {
        current = reduced;
        granularity = Math.max(granularity - 1, 2);
        continue;
      }
      if (granularity >= current.size()) {
        break;
      }
      granularity = Math.min(current.size(), granularity * 2);
    }
    return new Minimization<>(oracle.materialise(current), oracle.calls());
  }

  private static <T> List<Integer> firstFailing(List<List<Integer>> candidates, Oracle<T> oracle) {
    for (List<Integer> candidate : candidates) {
      if (oracle.test(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  static List<List<Integer>> split(List<Integer> items, int parts) {
    List<List<Integer>> chunks = new ArrayList<>(parts);
    int start = 0;
    for (int i = 0; i < parts; i++) {
      int end = start + (items.size() - start) / (parts - i);
      chunks.add(List.copyOf(items.subList(start, end)));
      start = end;
    }
    return chunks;
  }

  private static List<List<Integer>> complements(List<Integer> items, List<List<Integer>> chunks) {
    List<List<Integer>> complements = new ArrayList<>(chunks.size());
    for (List<Integer> chunk : chunks) {
      List<Integer> complement = new ArrayList<>(items);
      complement.removeAll(chunk);
      complements.add(complement);
    }
    return complements;
  }

  /** Memoising wrapper that works on index lists so that candidates have cheap, exact keys. */
  private static final class Oracle<T> {
    private final List<T> input;
    private final Predicate<List<T>> fails;
    private final Map<List<Integer>, Boolean> answers = new HashMap<>();

    Oracle(List<T> input, Predicate<List<T>> fails) {
      this.input = List.copyOf(input);
      this.fails = fails;
    }

    boolean test(List<Integer> candidate) {
      Boolean known = answers.get(candidate);
      if (known != null) {
        return known;
      }
      boolean answer = fails.test(materialise(candidate));
      answers.put(List.copyOf(candidate), answer);
      return answer;
    }

    List<T> materialise(List<Integer> indices) {
      return indices.stream().map(input::get).toList();
    }

    int calls() {
      return answers.size();
    }
  }
}
