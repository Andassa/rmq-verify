package io.github.andassa.rmqverify.workload;

import java.util.List;
import java.util.Map;

/**
 * How clients reach the brokers. In the test setup every port is a Toxiproxy proxy.
 *
 * @param host address of the published ports
 * @param amqpPorts AMQP ports, one per node
 * @param streamPortByNode stream port per advertised node host name
 * @param vhost virtual host
 * @param user application user
 * @param password application password
 * @param tls TLS material, empty for plain connections
 */
public record BrokerAccess(
    String host,
    List<Integer> amqpPorts,
    Map<String, Integer> streamPortByNode,
    String vhost,
    String user,
    String password,
    java.util.Optional<Tls> tls) {

  /** Copies the collections and checks presence. */
  public BrokerAccess {
    if (host == null
        || host.isBlank()
        || vhost == null
        || user == null
        || password == null
        || tls == null) {
      throw new IllegalArgumentException("host, vhost, user and password are required");
    }
    amqpPorts = List.copyOf(amqpPorts);
    streamPortByNode = Map.copyOf(streamPortByNode);
    if (amqpPorts.isEmpty()) {
      throw new IllegalArgumentException("at least one AMQP port is required");
    }
  }

  @Override
  public String toString() {
    return "BrokerAccess[host="
        + host
        + ", amqpPorts="
        + amqpPorts
        + ", streamPorts="
        + streamPortByNode
        + ", vhost="
        + vhost
        + ", user="
        + user
        + ", password=***, tls="
        + tls.isPresent()
        + "]";
  }
}
