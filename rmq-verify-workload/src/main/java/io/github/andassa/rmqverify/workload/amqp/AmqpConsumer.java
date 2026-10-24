package io.github.andassa.rmqverify.workload.amqp;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.workload.Backoff;
import io.github.andassa.rmqverify.workload.Pacer;
import io.github.andassa.rmqverify.workload.Worker;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Consumes one queue with manual acknowledgements and reconnects after any interruption. */
public final class AmqpConsumer implements Worker {

  private static final Logger LOG = LoggerFactory.getLogger(AmqpConsumer.class);
  private static final Duration POLL = Duration.ofMillis(200);

  private final String process;
  private final String queue;
  private final int prefetch;
  private final AmqpConnector connector;
  private final DeliveryHandler handler;
  private final Pacer pacer;
  private final Backoff backoff = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(2));
  private final AtomicBoolean stopped = new AtomicBoolean();

  /**
   * Creates a consumer.
   *
   * @param process logical process name
   * @param queue consumed queue
   * @param prefetch maximum unacknowledged deliveries
   * @param connector connection factory
   * @param recorder history recorder
   * @param pacer waits between checks and reconnections
   */
  public AmqpConsumer(
      String process,
      String queue,
      int prefetch,
      AmqpConnector connector,
      HistoryRecorder recorder,
      Pacer pacer) {
    this.process = process;
    this.queue = queue;
    this.prefetch = prefetch;
    this.connector = connector;
    this.handler = new DeliveryHandler(process, recorder);
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
        consumeUntilInterrupted();
        if (!stopped.get()) {
          pacer.pause(backoff.nextDelay());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void consumeUntilInterrupted() throws InterruptedException {
    Connection connection = null;
    try {
      connection = connector.open(process);
      Channel channel = connection.createChannel();
      channel.basicQos(prefetch);
      channel.basicConsume(
          queue,
          false,
          (tag, delivery) ->
              handler.handle(
                  channel,
                  queue,
                  delivery.getBody(),
                  delivery.getEnvelope().getDeliveryTag(),
                  delivery.getEnvelope().isRedeliver()),
          tag -> LOG.info("{}: consumer of {} cancelled by the broker", process, queue));
      backoff.reset();
      while (!stopped.get() && channel.isOpen()) {
        pacer.pause(POLL);
      }
    } catch (IOException | TimeoutException | ShutdownSignalException e) {
      LOG.debug("{}: consumption of {} interrupted: {}", process, queue, e.getMessage());
    } finally {
      AmqpResources.closeQuietly(connection);
    }
  }
}
