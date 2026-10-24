package io.github.andassa.rmqverify.workload.stream;

import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.NoOffsetException;
import com.rabbitmq.stream.OffsetSpecification;
import com.rabbitmq.stream.StreamException;
import com.rabbitmq.stream.StreamStats;
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
 * the committed offset reported by the stream leader (or, on brokers that only report the committed
 * chunk, that chunk followed by a quiet period); an empty stream is complete immediately.
 */
public final class StreamDrainer {

  private static final Logger LOG = LoggerFactory.getLogger(StreamDrainer.class);
  private static final Duration POLL = Duration.ofMillis(200);

  private final Environment environment;
  private final HistoryRecorder recorder;
  private final NanoClock clock;
  private final Pacer pacer;
  private final Duration timeout;
  private final Duration quiet;

  /**
   * Creates a drainer.
   *
   * @param environment stream environment
   * @param recorder history recorder
   * @param clock time source for the deadline
   * @param pacer waits between progress checks
   * @param timeout maximum duration of one drain
   * @param quiet silence that ends a drain when only the committed chunk is known
   */
  public StreamDrainer(
      Environment environment,
      HistoryRecorder recorder,
      NanoClock clock,
      Pacer pacer,
      Duration timeout,
      Duration quiet) {
    this.environment = environment;
    this.recorder = recorder;
    this.clock = clock;
    this.pacer = pacer;
    this.timeout = timeout;
    this.quiet = quiet;
  }

  /**
   * Drains one stream.
   *
   * @param stream the stream
   * @param process drain process name
   * @return whether the end criterion was met
   * @throws InterruptedException if interrupted
   */
  public boolean drain(String stream, String process) throws InterruptedException {
    recorder.invoke(process, OpFunction.DRAIN, new Value.Drain(stream, 0));
    long deadline = clock.nanoTime() + timeout.toNanos();
    StreamReader reader = new StreamReader(process, stream, recorder);
    Target target = target(stream, deadline);
    if (target == null) {
      return finish(process, stream, reader, OpType.INFO, "stream statistics unavailable");
    }
    if (target.offset() < 0) {
      return finish(process, stream, reader, OpType.OK, "");
    }
    Consumer consumer = null;
    try {
      consumer =
          environment.consumerBuilder().stream(stream)
              .offset(OffsetSpecification.first())
              .messageHandler(reader)
              .build();
      long lastCount = 0;
      long lastProgress = clock.nanoTime();
      while (clock.nanoTime() < deadline) {
        long now = clock.nanoTime();
        if (reader.count() != lastCount) {
          lastCount = reader.count();
          lastProgress = now;
        }
        boolean reached = reader.lastOffset() >= target.offset();
        if (reached && (target.exact() || now - lastProgress >= quiet.toNanos())) {
          return finish(process, stream, reader, OpType.OK, "");
        }
        pacer.pause(POLL);
      }
      return finish(
          process,
          stream,
          reader,
          OpType.INFO,
          "offset " + target.offset() + " not reached within " + timeout);
    } catch (StreamException e) {
      return finish(process, stream, reader, OpType.INFO, "drain failed: " + e.getMessage());
    } finally {
      if (consumer != null) {
        consumer.close();
      }
    }
  }

  /**
   * End of the log to reach. {@code exact} means the offset of the last committed message;
   * otherwise it is the first offset of the last committed chunk, and the drain also waits for a
   * quiet period so that the rest of that chunk arrives.
   */
  private record Target(long offset, boolean exact) {}

  /**
   * Returns the target, offset -1 for an empty stream, or null if the statistics never answered.
   */
  private Target target(String stream, long deadline) throws InterruptedException {
    while (clock.nanoTime() < deadline) {
      try {
        StreamStats stats = environment.queryStreamStats(stream);
        try {
          return new Target(stats.committedOffset(), true);
        } catch (NoOffsetException notReported) {
          // RabbitMQ 3.13 does not report the committed offset; the committed chunk is reported
          // whenever the stream holds data, so its absence too means the stream is empty.
          try {
            return new Target(stats.committedChunkId(), false);
          } catch (NoOffsetException empty) {
            return new Target(-1, true);
          }
        }
      } catch (StreamException e) {
        LOG.debug("stats of {} unavailable: {}", stream, e.getMessage());
        pacer.pause(POLL);
      }
    }
    return null;
  }

  private boolean finish(
      String process, String stream, StreamReader reader, OpType type, String error) {
    recorder.complete(
        process, type, OpFunction.DRAIN, new Value.Drain(stream, reader.count()), error);
    return type == OpType.OK;
  }
}
