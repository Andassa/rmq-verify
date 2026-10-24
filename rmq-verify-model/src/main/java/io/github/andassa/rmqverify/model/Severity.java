package io.github.andassa.rmqverify.model;

/** Weight of an anomaly in a verdict. */
public enum Severity {
  /** Breaks a guarantee; makes the verdict {@link VerdictStatus#INVALID}. */
  VIOLATION,
  /** Worth reporting (indeterminate outcome, legitimate redelivery) but not a violation. */
  NOTE
}
