package io.github.andassa.rmqverify.nemesis;

import java.time.Duration;

/** Waits; replaced by a virtual clock in tests. */
@FunctionalInterface
public interface Sleeper {

  /**
   * Waits for a duration; non-positive durations return immediately.
   *
   * @param duration how long
   * @throws InterruptedException if interrupted
   */
  void sleep(Duration duration) throws InterruptedException;

  /**
   * Returns the sleeper backed by {@link Thread#sleep(Duration)}.
   *
   * @return the real sleeper
   */
  static Sleeper real() {
    return d -> {
      if (d.isPositive()) {
        Thread.sleep(d);
      }
    };
  }
}
