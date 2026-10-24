package io.github.andassa.rmqverify.model;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A finding of a checker, with the operations that prove it.
 *
 * @param kind stable machine-readable identifier, for example {@code LOST_CONFIRMED}
 * @param severity whether the finding breaks a guarantee
 * @param description human-readable explanation
 * @param evidence identifiers of the operations that prove the finding, in history order
 */
public record Anomaly(String kind, Severity severity, String description, List<Long> evidence) {

  private static final Pattern KIND = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  /** Validates the components and copies the evidence. */
  public Anomaly {
    if (kind == null || !KIND.matcher(kind).matches()) {
      throw new IllegalArgumentException("kind must be UPPER_SNAKE_CASE: " + kind);
    }
    Checks.notNull(severity, "severity");
    Checks.text(description, "description");
    evidence = List.copyOf(Checks.notNull(evidence, "evidence"));
    if (severity == Severity.VIOLATION && evidence.isEmpty()) {
      throw new IllegalArgumentException("a violation requires evidence");
    }
  }

  /**
   * Tells whether this anomaly breaks a guarantee.
   *
   * @return {@code true} for violations
   */
  public boolean isViolation() {
    return severity == Severity.VIOLATION;
  }
}
