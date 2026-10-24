package io.github.andassa.rmqverify.workload.amqp;

import com.rabbitmq.client.Connection;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

/** Opens AMQP connections; a seam that lets tests replace the broker. */
@FunctionalInterface
public interface AmqpConnector {

  /**
   * Opens a new connection.
   *
   * @param clientName name shown in the management UI
   * @return an open connection, without automatic recovery
   * @throws IOException if no node accepts the connection
   * @throws TimeoutException if the handshake times out
   */
  Connection open(String clientName) throws IOException, TimeoutException;
}
