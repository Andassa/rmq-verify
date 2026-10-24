package io.github.andassa.rmqverify.model;

/** Conclusion of a checker. */
public enum VerdictStatus {
  /** The history exhibits no violation of the checked property. */
  VALID,
  /** At least one violation was found, with evidence. */
  INVALID,
  /** The history does not allow a conclusion. */
  UNKNOWN
}
