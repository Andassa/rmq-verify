package io.github.andassa.rmqverify.workload.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ShutdownSignalException;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.NanoClock;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.workload.Pacer;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Empties a destructive queue after the faults, to establish what survived. The drain is complete
 * (OK) only when the queue reports no ready message and no consumer for a full quiet period;
 * otherwise it ends with INFO at the deadline (METHODOLOGY 4.1, ADR-0009).
 */
public final class AmqpDrainer {

  private static final Logger LOG = LoggerFactory.getLogger(AmqpDrainer.class);
  private static final Duration EMPTY_POLL = Duration.ofMillis(200);

  private final AmqpConnector connector;
  private final HistoryRecorder recorder;
  private final NanoClock clock;
  private final Pacer pacer;
  private final Duration timeout;
  private final Duration quiet;

  /**
   * Creates a drainer.
   *
   * @param connector connection factory
   * @param recorder history recorder
   * @param clock time source for deadlines
   * @param pacer waits between polls
   * @param timeout maximum duration of one drain
   * @param quiet how long the queue must stay empty
   */
  public AmqpDrainer(
      AmqpConnector connector,
      HistoryRecorder recorder,
      NanoClock clock,
      Pacer pacer,
      Duration timeout,
      Duration quiet) {
    this.connector = connector;
    this.recorder = recorder;
    this.clock = clock;
    this.pacer = pacer;
    this.timeout = timeout;
    this.quiet = quiet;
  }

  /**
   * Drains one queue.
   *
   * @param queue the queue
   * @param process drain process name
   * @return whether the end criterion was met
   * @throws InterruptedException if interrupted
   */
  public boolean drain(String queue, String process) throws InterruptedException {
    recorder.invoke(process, OpFunction.DRAIN, new Value.Drain(queue, 0));
    DeliveryHandler handler = new DeliveryHandler(process, recorder);
    long deadline = clock.nanoTime() + timeout.toNanos();
    long received = 0;
    long emptySince = -1;
    Connection connection = null;
    Channel channel = null;
    try {
      while (clock.nanoTime() < deadline) {
        try {
          if (channel == null) {
            connection = connector.open(process);
            channel = connection.createChannel();
          }
          GetResponse response = channel.basicGet(queue, false);
          if (response != null) {
            handler.handle(
                channel,
                queue,
                response.getBody(),
                response.getEnvelope().getDeliveryTag(),
                response.getEnvelope().isRedeliver());
            received++;
            emptySince = -1;
            continue;
          }
          AMQP.Queue.DeclareOk state = channel.queueDeclarePassive(queue);
          long now = clock.nanoTime();
          if (state.getMessageCount() != 0 || state.getConsumerCount() != 0) {
            emptySince = -1;
          } else if (emptySince < 0) {
            emptySince = now;
          } else if (now - emptySince >= quiet.toNanos()) {
            recorder.complete(
                process, OpType.OK, OpFunction.DRAIN, new Value.Drain(queue, received), "");
            return true;
          }
          pacer.pause(EMPTY_POLL);
        } catch (IOException | TimeoutException | ShutdownSignalException e) {
          LOG.debug("drain of {} interrupted: {}", queue, e.getMessage());
          AmqpResources.closeQuietly(connection);
          connection = null;
          channel = null;
          emptySince = -1;
          pacer.pause(EMPTY_POLL);
        }
      }
      recorder.complete(
          process,
          OpType.INFO,
          OpFunction.DRAIN,
          new Value.Drain(queue, received),
          "end criterion not met within " + timeout);
      return false;
    } finally {
      AmqpResources.closeQuietly(connection);
    }
  }
}
