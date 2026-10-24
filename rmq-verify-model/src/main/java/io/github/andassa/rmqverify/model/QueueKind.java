package io.github.andassa.rmqverify.model;

/** RabbitMQ queue types compared by rmq-verify. */
public enum QueueKind {
  /** Classic queue: single leader, not replicated in RabbitMQ 4.x. */
  CLASSIC,
  /** Quorum queue: Raft-replicated. */
  QUORUM,
  /** Stream: replicated append-only log consumed by offset. */
  STREAM;

  /**
   * Tells whether consuming leaves messages in place, so that a drain re-reads the whole log.
   *
   * @return {@code true} for streams
   */
  public boolean isLog() {
    return this == STREAM;
  }
}
