package io.github.andassa.rmqverify.workload.amqp;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.workload.QueueSpec;
import io.github.andassa.rmqverify.workload.WorkloadException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Declares durable queues of every kind through AMQP 0-9-1. */
public final class QueueDeclarer {

  private final AmqpConnector connector;

  /**
   * Creates the declarer.
   *
   * @param connector connection factory
   */
  public QueueDeclarer(AmqpConnector connector) {
    this.connector = connector;
  }

  /**
   * Declares queues; declaring an existing queue with the same arguments is a no-op.
   *
   * @param queues the queues
   * @throws WorkloadException if a declaration fails
   */
  public void declare(List<QueueSpec> queues) throws WorkloadException {
    try (Connection connection = connector.open("rmq-verify-declare")) {
      Channel channel = connection.createChannel();
      for (QueueSpec queue : queues) {
        channel.queueDeclare(queue.name(), true, false, false, arguments(queue));
      }
    } catch (IOException | TimeoutException e) {
      throw new WorkloadException("cannot declare " + queues, e);
    }
  }

  static Map<String, Object> arguments(QueueSpec queue) {
    Map<String, Object> arguments = new HashMap<>();
    arguments.put("x-queue-type", queue.kind().name().toLowerCase(Locale.ROOT));
    if (queue.maxLength() > 0) {
      arguments.put("x-max-length", queue.maxLength());
      arguments.put("x-overflow", "drop-head");
    }
    if (queue.kind() == QueueKind.QUORUM) {
      arguments.put("x-quorum-initial-group-size", 3);
    }
    return arguments;
  }
}
