package io.github.andassa.rmqverify.model;

import java.io.IOException;

/** Receives operations as they are recorded, typically to stream them to disk. */
@FunctionalInterface
public interface OperationSink {

  /**
   * Accepts one operation. Calls are serialised by the recorder, in identifier order.
   *
   * @param operation the recorded operation
   * @throws IOException if the operation cannot be persisted
   */
  void accept(Operation operation) throws IOException;
}
