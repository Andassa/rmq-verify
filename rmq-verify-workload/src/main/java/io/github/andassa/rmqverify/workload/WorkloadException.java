package io.github.andassa.rmqverify.workload;

import java.io.Serial;

/** The workload could not be set up or completed. */
public final class WorkloadException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param cause underlying error
   */
  public WorkloadException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates the exception.
   *
   * @param message what failed
   */
  public WorkloadException(String message) {
    super(message);
  }
}
