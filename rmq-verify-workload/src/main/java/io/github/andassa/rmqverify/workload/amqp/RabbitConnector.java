package io.github.andassa.rmqverify.workload.amqp;

import com.rabbitmq.client.Address;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.github.andassa.rmqverify.workload.BrokerAccess;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeoutException;

/**
 * Connector backed by the official client. Automatic recovery is disabled on purpose: recovery
 * would silently re-establish channels and hide from the history the very interruptions we test.
 */
public final class RabbitConnector implements AmqpConnector {

  private final ConnectionFactory factory = new ConnectionFactory();
  private final List<Address> addresses;
  private final SplittableRandom shuffler;

  /**
   * Creates the connector.
   *
   * @param access broker endpoints and credentials
   * @param seed seed of the node order tried by each new connection
   */
  public RabbitConnector(BrokerAccess access, long seed) {
    factory.setUsername(access.user());
    factory.setPassword(access.password());
    factory.setVirtualHost(access.vhost());
    factory.setAutomaticRecoveryEnabled(false);
    factory.setTopologyRecoveryEnabled(false);
    factory.setRequestedHeartbeat(5);
    factory.setConnectionTimeout(5_000);
    factory.setHandshakeTimeout(5_000);
    factory.setShutdownTimeout(2_000);
    access
        .tls()
        .ifPresent(
            tls -> {
              factory.useSslProtocol(tls.amqp());
              factory.enableHostnameVerification();
            });
    List<Address> list = new ArrayList<>();
    for (int port : access.amqpPorts()) {
      list.add(new Address(access.host(), port));
    }
    this.addresses = List.copyOf(list);
    this.shuffler = new SplittableRandom(seed);
  }

  @Override
  public Connection open(String clientName) throws IOException, TimeoutException {
    List<Address> order = new ArrayList<>(addresses);
    synchronized (shuffler) {
      for (int i = order.size() - 1; i > 0; i--) {
        Collections.swap(order, i, shuffler.nextInt(i + 1));
      }
    }
    return factory.newConnection(order, clientName);
  }
}
