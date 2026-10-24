package io.github.andassa.rmqverify.checker.minimize;

import java.util.List;

/**
 * Result of a delta-debugging session.
 *
 * @param result the 1-minimal failing subsequence
 * @param tests number of distinct candidates submitted to the oracle
 * @param <T> element type
 */
public record Minimization<T>(List<T> result, int tests) {

  /** Copies the result. */
  public Minimization {
    result = List.copyOf(result);
  }
}
