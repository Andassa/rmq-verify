package io.github.andassa.rmqverify.cluster;

import java.io.Serial;

/** Failure to observe or act on the test cluster. */
public class ClusterException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed, with enough context to act on it
   */
  public ClusterException(String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what failed, with enough context to act on it
   * @param cause underlying error
   */
  public ClusterException(String message, Throwable cause) {
    super(message, cause);
  }
}
