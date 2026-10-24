package io.github.andassa.rmqverify.model;

import java.io.IOException;
import java.io.Serial;

/**
 * Thrown by {@link HistoryRecorder} when its sink fails. A run whose history cannot be persisted
 * must stop: continuing would produce a verdict on a truncated history.
 */
public final class HistoryWriteException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param operationId identifier of the operation that could not be written
   * @param cause the underlying I/O failure
   */
  public HistoryWriteException(long operationId, IOException cause) {
    super("cannot persist operation " + operationId, cause);
  }
}
