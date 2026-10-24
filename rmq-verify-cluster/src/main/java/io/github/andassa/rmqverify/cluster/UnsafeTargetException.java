package io.github.andassa.rmqverify.cluster;

import java.io.Serial;

/**
 * Raised by {@link SafetyGuard} when a target is not provably part of the test cluster. Faults are
 * never injected after this exception.
 */
public final class UnsafeTargetException extends ClusterException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why the target was refused
   */
  public UnsafeTargetException(String message) {
    super(message);
  }
}
