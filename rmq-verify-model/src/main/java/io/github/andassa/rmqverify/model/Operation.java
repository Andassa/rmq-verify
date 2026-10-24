package io.github.andassa.rmqverify.model;

import java.time.Instant;

/**
 * One entry of a history.
 *
 * @param id position-independent identifier, strictly increasing within a history
 * @param process logical client (publisher, consumer, drain, nemesis, prober)
 * @param type outcome type
 * @param function what the operation does
 * @param value payload, which must be accepted by {@code function}
 * @param timestampNanos monotonic clock reading, used for ordering and durations
 * @param wallClock wall-clock time, used to correlate with broker logs
 * @param error error description for {@code FAIL} and {@code INFO}, empty otherwise
 */
public record Operation(
    long id,
    String process,
    OpType type,
    OpFunction function,
    Value value,
    long timestampNanos,
    Instant wallClock,
    String error) {

  /** Validates the components and the legality of the type and function combination. */
  public Operation {
    Checks.nonNegative(id, "id");
    Checks.name(process, "process");
    Checks.notNull(type, "type");
    Checks.notNull(function, "function");
    Checks.notNull(value, "value");
    Checks.notNull(wallClock, "wallClock");
    Checks.text(error, "error");
    if (!value.accepts(function)) {
      throw new IllegalArgumentException(
          "operation "
              + id
              + ": "
              + function
              + " cannot carry "
              + value.getClass().getSimpleName());
    }
    if (type == OpType.INVOKE && !function.isInvocable()) {
      throw new IllegalArgumentException("operation " + id + ": " + function + " is never invoked");
    }
    if (type.isCompletion() && function == OpFunction.PUBLISH) {
      throw new IllegalArgumentException(
          "operation " + id + ": a publish completes with CONFIRM, not PUBLISH");
    }
  }

  /**
   * Tells whether this operation opens an invocation.
   *
   * @return {@code true} for {@code INVOKE}
   */
  public boolean isInvoke() {
    return type == OpType.INVOKE;
  }

  /**
   * Tells whether this operation closes an invocation, as opposed to being an observation.
   *
   * @return {@code true} for completions of {@code CONFIRM}, {@code ACK} and {@code DRAIN}
   */
  public boolean isCompletion() {
    return type.isCompletion() && !function.isObservation();
  }
}
