package io.github.andassa.rmqverify.model;

import java.time.Instant;

/** Source of monotonic and wall-clock time, injectable so that recorders are testable. */
public interface NanoClock {

  /**
   * Reads the monotonic clock.
   *
   * @return nanoseconds from an arbitrary origin; only differences are meaningful
   */
  long nanoTime();

  /**
   * Reads the wall clock.
   *
   * @return the current instant
   */
  Instant wallClock();

  /**
   * Returns the clock backed by {@link System#nanoTime()} and {@link Instant#now()}.
   *
   * @return the system clock
   */
  static NanoClock system() {
    return SystemNanoClock.INSTANCE;
  }
}
