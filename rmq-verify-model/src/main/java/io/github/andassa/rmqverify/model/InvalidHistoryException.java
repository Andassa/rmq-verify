package io.github.andassa.rmqverify.model;

import java.io.Serial;

/** Thrown when a sequence of operations violates a history invariant. */
public final class InvalidHistoryException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final long operationId;

  /**
   * Creates the exception.
   *
   * @param operationId identifier of the offending operation
   * @param message description of the violated invariant
   */
  public InvalidHistoryException(long operationId, String message) {
    super("operation " + operationId + ": " + message);
    this.operationId = operationId;
  }

  /**
   * Returns the identifier of the operation that broke the invariant.
   *
   * @return the operation identifier
   */
  public long operationId() {
    return operationId;
  }
}
