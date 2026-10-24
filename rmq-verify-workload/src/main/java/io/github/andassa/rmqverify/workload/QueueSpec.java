package io.github.andassa.rmqverify.workload;

import io.github.andassa.rmqverify.model.QueueKind;
import java.util.regex.Pattern;

/**
 * A queue exercised by the workload.
 *
 * @param name queue name; must start with {@code rmqv.} to match the application permissions
 * @param kind queue type
 * @param maxLength {@code x-max-length} with {@code drop-head} overflow, or 0 for unbounded. A
 *     bound makes the broker drop confirmed messages on purpose: it is the known-loss control
 *     scenario.
 */
public record QueueSpec(String name, QueueKind kind, long maxLength) {

  private static final Pattern NAME = Pattern.compile("rmqv\\.[a-zA-Z0-9_.-]{1,200}");

  /** Validates the name and the bound. */
  public QueueSpec {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("queue name must match " + NAME + ": " + name);
    }
    if (kind == null) {
      throw new IllegalArgumentException("kind must not be null");
    }
    if (maxLength < 0 || (maxLength > 0 && kind == QueueKind.STREAM)) {
      throw new IllegalArgumentException("maxLength must be >= 0 and is not supported for streams");
    }
  }
}
