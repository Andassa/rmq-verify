package io.github.andassa.rmqverify.workload.amqp;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closing helpers: a failed close of an already broken or closing connection is expected, not an
 * error. The client signals it with {@code ShutdownSignalException} (including its subclass {@code
 * AlreadyClosedException}), which must never escape and kill a client thread.
 */
final class AmqpResources {

  private static final Logger LOG = LoggerFactory.getLogger(AmqpResources.class);

  private AmqpResources() {}

  static void closeQuietly(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close(1_000);
    } catch (IOException | ShutdownSignalException e) {
      LOG.debug(
          "connection {} already broken: {}", connection.getClientProvidedName(), e.getMessage());
    }
  }
}
