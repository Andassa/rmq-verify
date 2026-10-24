package io.github.andassa.rmqverify.workload.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;
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
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes one persistent, mandatory message at a time and waits for its confirm, so that every
 * outcome is attributed to exactly one message. Errors are recorded, never retried: a retry would
 * publish a second copy and hide the behaviour under test (ADR-0004, ADR-0008).
 */
public final class AmqpPublisher implements Worker {

  private static final Logger LOG = LoggerFactory.getLogger(AmqpPublisher.class);

  private final String process;
  private final String runId;
  private final PublisherPlan plan;
  private final AmqpConnector connector;
  private final HistoryRecorder recorder;
  private final Duration confirmTimeout;
  private final Pacer pacer;
  private final Backoff backoff = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(2));
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final AtomicBoolean returned = new AtomicBoolean();
  private Connection connection;
  private Channel channel;

  /**
   * Creates a publisher.
   *
   * @param process logical process name, also the publisher identifier in payloads
   * @param runId run identifier
   * @param plan deterministic message plan
   * @param connector connection factory
   * @param recorder history recorder
   * @param confirmTimeout maximum wait for a confirm
   * @param pacer waits between publications
   */
  public AmqpPublisher(
      String process,
      String runId,
      PublisherPlan plan,
      AmqpConnector connector,
      HistoryRecorder recorder,
      Duration confirmTimeout,
      Pacer pacer) {
    this.process = process;
    this.runId = runId;
    this.plan = plan;
    this.connector = connector;
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
        if (channel == null && !connect()) {
          continue;
        }
        try {
          publish(
              new Message(runId, process, planned.queue(), planned.sequence(), planned.uuid()),
              planned.size());
        } catch (RuntimeException e) {
          // A client bug or an unexpected client state must not end the workload: the invocation
          // stays pending in the history, which every checker treats as indeterminate.
          LOG.warn("{}: unexpected client failure, reconnecting: {}", process, e.toString());
          disconnect();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      disconnect();
    }
  }

  private boolean connect() throws InterruptedException {
    try {
      connection = connector.open(process);
      channel = connection.createChannel();
      channel.confirmSelect();
      channel.addReturnListener(r -> returned.set(true));
      backoff.reset();
      return true;
    } catch (IOException | TimeoutException | ShutdownSignalException e) {
      LOG.debug("{} cannot connect: {}", process, e.getMessage());
      disconnect();
      pacer.pause(backoff.nextDelay());
      return false;
    }
  }

  private void publish(Message message, int size) throws InterruptedException {
    Value.Publication value = new Value.Publication(message);
    byte[] body = MessageCodec.encode(message, size);
    AMQP.BasicProperties properties =
        new AMQP.BasicProperties.Builder()
            .deliveryMode(2)
            .messageId(message.uuid())
            .contentType("application/json")
            .build();
    recorder.invoke(process, OpFunction.PUBLISH, value);
    returned.set(false);
    try {
      channel.basicPublish("", message.queue(), true, properties, body);
      boolean acked = channel.waitForConfirms(confirmTimeout.toMillis());
      if (returned.get()) {
        complete(OpType.FAIL, value, "returned: unroutable");
      } else if (acked) {
        complete(OpType.OK, value, "");
      } else {
        complete(OpType.FAIL, value, "nacked by broker");
      }
    } catch (TimeoutException e) {
      complete(OpType.INFO, value, "no confirm within " + confirmTimeout.toMillis() + " ms");
      disconnect();
    } catch (IOException | ShutdownSignalException e) {
      complete(OpType.INFO, value, e.getClass().getSimpleName() + ": " + e.getMessage());
      disconnect();
    } catch (InterruptedException e) {
      complete(OpType.INFO, value, "interrupted while waiting for the confirm");
      throw e;
    }
  }

  private void complete(OpType type, Value.Publication value, String error) {
    recorder.complete(process, type, OpFunction.CONFIRM, value, error);
  }

  private void disconnect() {
    AmqpResources.closeQuietly(connection);
    connection = null;
    channel = null;
  }
}
