package io.github.andassa.rmqverify.cluster;

/**
 * Outcome of an external command.
 *
 * @param exitCode process exit status
 * @param stdout captured standard output, truncated to a bounded size
 * @param stderr captured standard error, truncated to a bounded size
 */
public record ProcessResult(int exitCode, String stdout, String stderr) {

  /**
   * Tells whether the command succeeded.
   *
   * @return {@code true} when the exit status is zero
   */
  public boolean succeeded() {
    return exitCode == 0;
  }
}
