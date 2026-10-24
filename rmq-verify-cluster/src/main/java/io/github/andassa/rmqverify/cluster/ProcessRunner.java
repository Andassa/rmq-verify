package io.github.andassa.rmqverify.cluster;

import java.time.Duration;
import java.util.List;

/**
 * Runs external commands from structured arguments. Implementations must never involve a shell:
 * each element of {@code command} reaches the program as one argument, whatever it contains.
 */
@FunctionalInterface
public interface ProcessRunner {

  /**
   * Runs a command to completion.
   *
   * @param command program and arguments
   * @param timeout maximum duration; the process is killed afterwards
   * @return the exit status and captured output
   * @throws ClusterException if the process cannot be started, times out or is interrupted
   */
  ProcessResult run(List<String> command, Duration timeout) throws ClusterException;
}
