package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.model.VerdictStatus;

/** Process exit codes, stable for scripts and CI. */
final class ExitCodes {

  /** Every checker returned VALID. */
  static final int VALID = 0;

  /** At least one checker returned INVALID. */
  static final int INVALID = 1;

  /** Invalid command line (picocli's convention). */
  static final int USAGE = 2;

  /** No violation, but at least one checker was inconclusive. */
  static final int UNKNOWN = 3;

  /** The tool itself failed (cluster unreachable, unsafe target, unreadable file). */
  static final int FAILURE = 4;

  private ExitCodes() {}

  static int of(VerdictStatus status) {
    return switch (status) {
      case VALID -> VALID;
      case INVALID -> INVALID;
      case UNKNOWN -> UNKNOWN;
    };
  }
}
