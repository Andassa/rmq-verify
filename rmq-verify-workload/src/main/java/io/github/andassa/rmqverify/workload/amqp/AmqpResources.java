package io.github.andassa.rmqverify.workload.amqp;

import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Connection;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Closing helpers: a failed close of an already broken connection is expected, not an error. */
final class AmqpResources {

  private static final Logger LOG = LoggerFactory.getLogger(AmqpResources.class);

  private AmqpResources() {}

  static void closeQuietly(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close(1_000);
    } catch (IOException | AlreadyClosedException e) {
      LOG.debug(
          "connection {} already broken: {}", connection.getClientProvidedName(), e.getMessage());
    }
  }
}
