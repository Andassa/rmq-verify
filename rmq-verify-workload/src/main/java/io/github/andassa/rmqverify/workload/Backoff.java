package io.github.andassa.rmqverify.workload;

import java.time.Duration;

/** Exponential reconnection delay, capped, reset after a success. */
public final class Backoff {

  private final Duration initial;
  private final Duration max;
  private Duration next;

  /**
   * Creates a backoff.
   *
   * @param initial first delay
   * @param max cap
   */
  public Backoff(Duration initial, Duration max) {
    this.initial = initial;
    this.max = max;
    this.next = initial;
  }

  /**
   * Returns the delay to wait now and doubles the following one.
   *
   * @return the current delay
   */
  public Duration nextDelay() {
    Duration current = next;
    Duration doubled = next.multipliedBy(2);
    next = doubled.compareTo(max) > 0 ? max : doubled;
    return current;
  }

  /** Resets the delay after a successful connection. */
  public void reset() {
    next = initial;
  }
}
