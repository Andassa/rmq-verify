package io.github.andassa.rmqverify.checker;

import java.time.Duration;

/**
 * Thresholds that are expectations of the operator rather than RabbitMQ guarantees.
 *
 * @param availabilityWindow width of the windows used to compute success rates
 * @param maxRecovery longest acceptable time between a heal and the next confirmed publish
 * @param settle delay after a heal during which divergent cluster views are still tolerated
 */
public record CheckerContext(Duration availabilityWindow, Duration maxRecovery, Duration settle) {

  /** Validates that every duration is positive (or zero for {@code settle}). */
  public CheckerContext {
    if (availabilityWindow == null
        || availabilityWindow.isNegative()
        || availabilityWindow.isZero()) {
      throw new IllegalArgumentException("availabilityWindow must be positive");
    }
    if (maxRecovery == null || maxRecovery.isNegative() || maxRecovery.isZero()) {
      throw new IllegalArgumentException("maxRecovery must be positive");
    }
    if (settle == null || settle.isNegative()) {
      throw new IllegalArgumentException("settle must not be negative");
    }
  }

  /**
   * Returns the defaults documented in {@code docs/METHODOLOGY.md}: one-second windows, 60 seconds
   * of tolerated recovery and 15 seconds of settling after a heal.
   *
   * @return the default context
   */
  public static CheckerContext defaults() {
    return new CheckerContext(
        Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(15));
  }
}
