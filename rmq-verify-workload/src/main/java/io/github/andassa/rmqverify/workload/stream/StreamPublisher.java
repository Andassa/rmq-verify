package io.github.andassa.rmqverify.workload.stream;

import com.rabbitmq.stream.ConfirmationStatus;
import com.rabbitmq.stream.Constants;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Producer;
import com.rabbitmq.stream.StreamException;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.workload.Backoff;
import io.github.andassa.rmqverify.workload.MessageCodec;
import io.github.andassa.rmqverify.workload.Pacer;
import io.github.andassa.rmqverify.workload.PublisherPlan;
import io.github.andassa.rmqverify.workload.Worker;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes to streams with the stream protocol, one message at a time, waiting for its
 * confirmation. Client-side give-ups (timeouts, producer unavailable) are indeterminate; only an
 * error answered by the broker is a definitive failure.
 */
public final class StreamPublisher implements Worker {

  private static final Logger LOG = LoggerFactory.getLogger(StreamPublisher.class);

  private final String process;
  private final String runId;
  private final PublisherPlan plan;
  private final Environment environment;
  private final HistoryRecorder recorder;
  private final Duration confirmTimeout;
  private final Pacer pacer;
  private final Backoff backoff = new Backoff(Duration.ofMillis(200), Duration.ofSeconds(2));
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final Map<String, Producer> producers = new HashMap<>();

  /**
   * Creates a publisher.
   *
   * @param process logical process name, also the publisher identifier in payloads
   * @param runId run identifier
   * @param plan deterministic message plan
   * @param environment shared stream environment
   * @param recorder history recorder
   * @param confirmTimeout maximum wait for a confirmation
   * @param pacer waits between publications
   */
  public StreamPublisher(
      String process,
      String runId,
      PublisherPlan plan,
      Environment environment,
      HistoryRecorder recorder,
      Duration confirmTimeout,
      Pacer pacer) {
    this.process = process;
    this.runId = runId;
    this.plan = plan;
    this.environment = environment;
    this.recorder = recorder;
    this.confirmTimeout = confirmTimeout;
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
    try {
      while (!stopped.get()) {
        PublisherPlan.Planned planned = plan.next();
        pacer.pause(Duration.ofNanos(planned.pauseNanos()));
        if (stopped.get()) {
          break;
        }
        Producer producer = producer(planned.queue());
        if (producer != null) {
          try {
            publish(
                producer,
                new Message(runId, process, planned.queue(), planned.sequence(), planned.uuid()),
                planned.size());
          } catch (RuntimeException e) {
            // Same policy as the AMQP publisher: never end the workload on a client surprise.
            LOG.warn("{}: unexpected client failure, new producer: {}", process, e.toString());
            dropProducer(planned.queue());
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      producers.values().forEach(StreamPublisher::closeQuietly);
    }
  }

  private Producer producer(String stream) throws InterruptedException {
    Producer existing = producers.get(stream);
    if (existing != null) {
      return existing;
    }
    try {
      Producer created =
          environment.producerBuilder().stream(stream).confirmTimeout(confirmTimeout).build();
      producers.put(stream, created);
      backoff.reset();
      return created;
    } catch (StreamException e) {
      LOG.debug("{}: no producer for {}: {}", process, stream, e.getMessage());
      pacer.pause(backoff.nextDelay());
      return null;
    }
  }

  private void publish(Producer producer, Message message, int size) throws InterruptedException {
    Value.Publication value = new Value.Publication(message);
    CompletableFuture<ConfirmationStatus> confirmation = new CompletableFuture<>();
    recorder.invoke(process, OpFunction.PUBLISH, value);
    try {
      producer.send(
          producer.messageBuilder().addData(MessageCodec.encode(message, size)).build(),
          confirmation::complete);
      ConfirmationStatus status =
          confirmation.get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (status.isConfirmed()) {
        complete(OpType.OK, value, "");
      } else {
        complete(outcomeOf(status.getCode()), value, "confirmation code " + status.getCode());
        dropProducer(message.queue());
      }
    } catch (TimeoutException e) {
      complete(OpType.INFO, value, "no confirmation within " + confirmTimeout.toMillis() + " ms");
      dropProducer(message.queue());
    } catch (ExecutionException | StreamException e) {
      complete(OpType.INFO, value, e.getClass().getSimpleName() + ": " + e.getMessage());
      dropProducer(message.queue());
    } catch (InterruptedException e) {
      complete(OpType.INFO, value, "interrupted while waiting for the confirmation");
      throw e;
    }
  }

  /** Codes produced by the client itself mean "gave up", not "refused": the outcome is unknown. */
  static OpType outcomeOf(short code) {
    boolean clientSide =
        code == Constants.CODE_PUBLISH_CONFIRM_TIMEOUT
            || code == Constants.CODE_PRODUCER_NOT_AVAILABLE
            || code == Constants.CODE_PRODUCER_CLOSED
            || code == Constants.CODE_MESSAGE_ENQUEUEING_FAILED;
    return clientSide ? OpType.INFO : OpType.FAIL;
  }

  private void complete(OpType type, Value.Publication value, String error) {
    recorder.complete(process, type, OpFunction.CONFIRM, value, error);
  }

  private void dropProducer(String stream) {
    closeQuietly(producers.remove(stream));
  }

  private static void closeQuietly(Producer producer) {
    if (producer == null) {
      return;
    }
    try {
      producer.close();
    } catch (StreamException e) {
      LOG.debug("producer already broken: {}", e.getMessage());
    }
  }
}
