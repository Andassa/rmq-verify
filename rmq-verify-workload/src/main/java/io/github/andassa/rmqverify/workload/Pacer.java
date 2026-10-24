package io.github.andassa.rmqverify.workload;

import java.time.Duration;

/** Waits between operations; replaced by a no-op in tests. */
@FunctionalInterface
public interface Pacer {

  /**
   * Waits.
   *
   * @param duration how long
   * @throws InterruptedException if the thread is interrupted
   */
  void pause(Duration duration) throws InterruptedException;

  /**
   * Returns the pacer backed by {@link Thread#sleep(Duration)}.
   *
   * @return the real pacer
   */
  static Pacer sleeping() {
    return Thread::sleep;
  }
}
