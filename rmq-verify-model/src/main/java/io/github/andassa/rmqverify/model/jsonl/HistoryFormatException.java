package io.github.andassa.rmqverify.model.jsonl;

import java.io.IOException;
import java.io.Serial;

/** Thrown when a history file is malformed, too large or violates the schema. */
public final class HistoryFormatException extends IOException {

  @Serial private static final long serialVersionUID = 1L;

  private final long line;

  /**
   * Creates the exception.
   *
   * @param line one-based line number, or {@code 0} when the error concerns the whole file
   * @param message description of the problem
   */
  public HistoryFormatException(long line, String message) {
    super(line > 0 ? "line " + line + ": " + message : message);
    this.line = line;
  }

  /**
   * Creates the exception with a cause.
   *
   * @param line one-based line number, or {@code 0} when the error concerns the whole file
   * @param message description of the problem
   * @param cause underlying error
   */
  public HistoryFormatException(long line, String message, Throwable cause) {
    super(line > 0 ? "line " + line + ": " + message : message, cause);
    this.line = line;
  }

  /**
   * Returns the line at fault.
   *
   * @return one-based line number, {@code 0} for file-level errors
   */
  public long line() {
    return line;
  }
}
