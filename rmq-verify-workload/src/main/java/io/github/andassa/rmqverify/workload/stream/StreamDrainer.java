package io.github.andassa.rmqverify.workload.stream;

import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.NoOffsetException;
import com.rabbitmq.stream.OffsetSpecification;
import com.rabbitmq.stream.StreamException;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.NanoClock;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.workload.Pacer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-reads a whole stream from its first offset. The drain is complete when the reader has reached
 * the committed offset reported by the stream leader; an empty stream is complete immediately.
 */
public final class StreamDrainer {

  private static final Logger LOG = LoggerFactory.getLogger(StreamDrainer.class);
  private static final Duration POLL = Duration.ofMillis(200);

  private final Environment environment;
  private final HistoryRecorder recorder;
  private final NanoClock clock;
  private final Pacer pacer;
  private final Duration timeout;

  /**
   * Creates a drainer.
   *
   * @param environment stream environment
   * @param recorder history recorder
   * @param clock time source for the deadline
   * @param pacer waits between progress checks
   * @param timeout maximum duration of one drain
   */
  public StreamDrainer(
      Environment environment,
      HistoryRecorder recorder,
      NanoClock clock,
      Pacer pacer,
      Duration timeout) {
    this.environment = environment;
    this.recorder = recorder;
    this.clock = clock;
    this.pacer = pacer;
    this.timeout = timeout;
  }

  /**
   * Drains one stream.
   *
   * @param stream the stream
   * @param process drain process name
   * @return whether the committed offset was reached
   * @throws InterruptedException if interrupted
   */
  public boolean drain(String stream, String process) throws InterruptedException {
    recorder.invoke(process, OpFunction.DRAIN, new Value.Drain(stream, 0));
    long deadline = clock.nanoTime() + timeout.toNanos();
    long committed = committedOffset(stream, deadline);
    StreamReader reader = new StreamReader(process, stream, recorder);
    if (committed == Long.MIN_VALUE) {
      return finish(process, stream, reader, OpType.INFO, "committed offset unavailable");
    }
    if (committed < 0) {
      return finish(process, stream, reader, OpType.OK, "");
    }
    Consumer consumer = null;
    try {
      consumer =
          environment.consumerBuilder().stream(stream)
              .offset(OffsetSpecification.first())
              .messageHandler(reader)
              .build();
      while (clock.nanoTime() < deadline) {
        if (reader.lastOffset() >= committed) {
          return finish(process, stream, reader, OpType.OK, "");
        }
        pacer.pause(POLL);
      }
      return finish(
          process,
          stream,
          reader,
          OpType.INFO,
          "offset " + committed + " not reached within " + timeout);
    } catch (StreamException e) {
      return finish(process, stream, reader, OpType.INFO, "drain failed: " + e.getMessage());
    } finally {
      if (consumer != null) {
        consumer.close();
      }
    }
  }

  /** Returns the committed offset, -1 for an empty stream, or {@code Long.MIN_VALUE} if unknown. */
  private long committedOffset(String stream, long deadline) throws InterruptedException {
    while (clock.nanoTime() < deadline) {
      try {
        return environment.queryStreamStats(stream).committedOffset();
      } catch (NoOffsetException e) {
        return -1;
      } catch (StreamException e) {
        LOG.debug("stats of {} unavailable: {}", stream, e.getMessage());
        pacer.pause(POLL);
      }
    }
    return Long.MIN_VALUE;
  }

  private boolean finish(
      String process, String stream, StreamReader reader, OpType type, String error) {
    recorder.complete(
        process, type, OpFunction.DRAIN, new Value.Drain(stream, reader.count()), error);
    return type == OpType.OK;
  }
}
