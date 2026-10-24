package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.Operation;
import java.util.Optional;

/**
 * Interval during which one fault of the plan was active.
 *
 * @param index position of the fault in the plan
 * @param nemesis name of the nemesis
 * @param start the {@code NEMESIS_START} operation
 * @param stop the matching {@code NEMESIS_STOP}, empty if the fault was never healed in the history
 */
public record FaultWindow(int index, String nemesis, Operation start, Optional<Operation> stop) {

  /**
   * Tells whether the fault was healed within the history.
   *
   * @return {@code true} if a stop operation exists
   */
  public boolean closed() {
    return stop.isPresent();
  }

  /**
   * Returns the monotonic time at which the fault ended.
   *
   * @param endOfHistory value to use for a fault that was never healed
   * @return the stop timestamp, or {@code endOfHistory}
   */
  public long endNanos(long endOfHistory) {
    return stop.map(Operation::timestampNanos).orElse(endOfHistory);
  }

  /**
   * Tells whether an instant falls within the fault, extended by a grace period after the heal.
   *
   * @param nanos monotonic time
   * @param graceNanos tolerance after the stop
   * @param endOfHistory end used for an unhealed fault
   * @return {@code true} if {@code start <= nanos <= stop + grace}
   */
  public boolean covers(long nanos, long graceNanos, long endOfHistory) {
    return nanos >= start.timestampNanos() && nanos <= endNanos(endOfHistory) + graceNanos;
  }
}
