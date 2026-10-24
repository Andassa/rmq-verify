package io.github.andassa.rmqverify.workload.stream;

import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.OffsetSpecification;
import com.rabbitmq.stream.StreamException;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.workload.Backoff;
import io.github.andassa.rmqverify.workload.Pacer;
import io.github.andassa.rmqverify.workload.Worker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a stream from its first offset. The stream client recovers the subscription after a failure
 * and resumes from the last dispatched offset, which may replay that offset once.
 */
public final class StreamConsumer implements Worker {

  private static final Logger LOG = LoggerFactory.getLogger(StreamConsumer.class);

  private final String process;
  private final Environment environment;
  private final StreamReader reader;
  private final String stream;
  private final Pacer pacer;
  private final AtomicBoolean stopped = new AtomicBoolean();

  /**
   * Creates a consumer.
   *
   * @param process logical process name
   * @param stream consumed stream
   * @param environment shared stream environment
   * @param recorder history recorder
   * @param pacer waits while consuming
   */
  public StreamConsumer(
      String process,
      String stream,
      Environment environment,
      HistoryRecorder recorder,
      Pacer pacer) {
    this.process = process;
    this.stream = stream;
    this.environment = environment;
    this.reader = new StreamReader(process, stream, recorder);
    this.pacer = pacer;
  }

  @Override
  public String process() {
    return process;
  }

  @Override
  public void stop() {
    stopped.set(true);
  }

  @Override
  public void run() {
    Backoff backoff = new Backoff(Duration.ofMillis(200), Duration.ofSeconds(2));
    Consumer consumer = null;
    try {
      while (!stopped.get()) {
        if (consumer == null) {
          try {
            consumer =
                environment.consumerBuilder().stream(stream)
                    .offset(OffsetSpecification.first())
                    .messageHandler(reader)
                    .build();
            backoff.reset();
          } catch (StreamException e) {
            LOG.debug("{}: cannot subscribe to {}: {}", process, stream, e.getMessage());
            pacer.pause(backoff.nextDelay());
            continue;
          }
        }
        pacer.pause(Duration.ofMillis(200));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      if (consumer != null) {
        consumer.close();
      }
    }
  }
}
