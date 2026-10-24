package io.github.andassa.rmqverify.model;

/** Outcome type of an operation, following the Jepsen convention. */
public enum OpType {
  /** The process is about to perform an invocation; its outcome is not known yet. */
  INVOKE,
  /** Definitive success. */
  OK,
  /** Definitive failure: the system explicitly refused the operation. */
  FAIL,
  /**
   * Indeterminate outcome: the operation may or may not have taken effect (timeout, connection
   * lost). It must never be interpreted as a failure.
   */
  INFO;

  /**
   * Tells whether this type closes an invocation.
   *
   * @return {@code true} for {@code OK}, {@code FAIL} and {@code INFO}
   */
  public boolean isCompletion() {
    return this != INVOKE;
  }
}
