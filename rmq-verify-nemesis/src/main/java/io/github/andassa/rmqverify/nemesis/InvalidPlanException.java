package io.github.andassa.rmqverify.nemesis;

import java.io.Serial;

/** A fault plan file is malformed, too large or refers to unknown nemeses or nodes. */
public final class InvalidPlanException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong
   */
  public InvalidPlanException(String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what is wrong
   * @param cause parsing or validation error
   */
  public InvalidPlanException(String message, Throwable cause) {
    super(message, cause);
  }
}
