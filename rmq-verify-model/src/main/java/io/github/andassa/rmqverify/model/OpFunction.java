package io.github.andassa.rmqverify.model;

import java.util.Optional;

/**
 * What an operation does. Functions are either <em>invocations</em> (an {@code INVOKE} followed by
 * at most one completion) or <em>observations</em> (a single record of something seen).
 */
public enum OpFunction {
  /** Publication of a message; completed by a {@link #CONFIRM}. */
  PUBLISH,
  /** Publisher confirm outcome of a {@link #PUBLISH}; only ever a completion. */
  CONFIRM,
  /** A message handed to a consumer; an observation. */
  DELIVER,
  /** Acknowledgement of a delivery; an invocation completed by another {@code ACK}. */
  ACK,
  /** Final drain of one queue; an invocation completed by another {@code DRAIN}. */
  DRAIN,
  /** A fault has been injected; an observation recorded by the nemesis. */
  NEMESIS_START,
  /** A fault has been healed; an observation recorded by the nemesis. */
  NEMESIS_STOP,
  /** A view of the cluster read from one node's management API; an observation. */
  PROBE;

  /**
   * Tells whether this function is a single-operation observation.
   *
   * @return {@code true} if operations with this function are never invoked
   */
  public boolean isObservation() {
    return this == DELIVER || this == NEMESIS_START || this == NEMESIS_STOP || this == PROBE;
  }

  /**
   * Tells whether an {@code INVOKE} of this function is legal.
   *
   * @return {@code true} for {@code PUBLISH}, {@code ACK} and {@code DRAIN}
   */
  public boolean isInvocable() {
    return this == PUBLISH || this == ACK || this == DRAIN;
  }

  /**
   * Returns the function that completes an invocation of this function.
   *
   * @return the completing function, empty when this function cannot be invoked
   */
  public Optional<OpFunction> completedBy() {
    return switch (this) {
      case PUBLISH -> Optional.of(CONFIRM);
      case ACK -> Optional.of(ACK);
      case DRAIN -> Optional.of(DRAIN);
      default -> Optional.empty();
    };
  }
}
