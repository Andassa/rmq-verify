package io.github.andassa.rmqverify.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe, append-only recorder. A single lock assigns identifiers and timestamps and forwards
 * each operation to the sink, so that the recording order is a real-time order: if {@code a}
 * returned before {@code b} was called, then {@code a.id() < b.id()}. A {@link ReentrantLock} is
 * used rather than {@code synchronized} so that virtual threads do not pin their carrier while the
 * sink performs I/O.
 */
public final class HistoryRecorder {

  private final HistoryHeader header;
  private final NanoClock clock;
  private final OperationSink sink;
  private final ReentrantLock lock = new ReentrantLock();
  private final List<Operation> operations = new ArrayList<>();
  private long nextId;
  private long lastTimestamp = Long.MIN_VALUE;

  /**
   * Creates a recorder.
   *
   * @param header metadata of the run
   * @param clock time source
   * @param sink receiver of every operation, called under the recorder lock
   */
  public HistoryRecorder(HistoryHeader header, NanoClock clock, OperationSink sink) {
    this.header = Checks.notNull(header, "header");
    this.clock = Checks.notNull(clock, "clock");
    this.sink = Checks.notNull(sink, "sink");
  }

  /**
   * Returns the metadata of the run being recorded.
   *
   * @return the header
   */
  public HistoryHeader header() {
    return header;
  }

  /**
   * Records an invocation.
   *
   * @param process logical client
   * @param function invoked function
   * @param value payload
   * @return the recorded operation
   */
  public Operation invoke(String process, OpFunction function, Value value) {
    return record(process, OpType.INVOKE, function, value, "");
  }

  /**
   * Records the completion of an invocation.
   *
   * @param process logical client that invoked
   * @param type {@code OK}, {@code FAIL} or {@code INFO}
   * @param function completing function
   * @param value payload, identical to the invocation's except for drains
   * @param error error description, empty for {@code OK}
   * @return the recorded operation
   */
  public Operation complete(
      String process, OpType type, OpFunction function, Value value, String error) {
    if (!type.isCompletion()) {
      throw new IllegalArgumentException("a completion cannot be of type " + type);
    }
    return record(process, type, function, value, error);
  }

  /**
   * Records a successful observation.
   *
   * @param process observer
   * @param function observed function
   * @param value payload
   * @return the recorded operation
   */
  public Operation observe(String process, OpFunction function, Value value) {
    return record(process, OpType.OK, function, value, "");
  }

  /**
   * Records an operation. Errors longer than the domain limit are truncated.
   *
   * @param process logical client
   * @param type outcome type
   * @param function function
   * @param value payload
   * @param error error description
   * @return the recorded operation
   * @throws HistoryWriteException if the sink fails
   */
  public Operation record(
      String process, OpType type, OpFunction function, Value value, String error) {
    String boundedError =
        error.length() > Checks.MAX_TEXT_LENGTH
            ? error.substring(0, Checks.MAX_TEXT_LENGTH)
            : error;
    lock.lock();
    try {
      long timestamp = Math.max(lastTimestamp, clock.nanoTime());
      Operation op =
          new Operation(
              nextId, process, type, function, value, timestamp, clock.wallClock(), boundedError);
      sink.accept(op);
      operations.add(op);
      nextId++;
      lastTimestamp = timestamp;
      return op;
    } catch (IOException e) {
      throw new HistoryWriteException(nextId, e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the number of operations recorded so far.
   *
   * @return the count
   */
  public int size() {
    lock.lock();
    try {
      return operations.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Builds a validated history from everything recorded so far.
   *
   * @return the history
   * @throws InvalidHistoryException if the recorded operations break an invariant
   */
  public History snapshot() {
    lock.lock();
    try {
      return new History(header, operations);
    } finally {
      lock.unlock();
    }
  }
}
