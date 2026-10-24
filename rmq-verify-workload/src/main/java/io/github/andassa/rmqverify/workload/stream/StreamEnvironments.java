package io.github.andassa.rmqverify.workload.stream;

import com.rabbitmq.stream.Address;
import com.rabbitmq.stream.BackOffDelayPolicy;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.EnvironmentBuilder;
import io.github.andassa.rmqverify.workload.BrokerAccess;
import java.time.Duration;
import java.util.List;

/** Builds stream environments that reach advertised node names through their local proxies. */
public final class StreamEnvironments {

  private StreamEnvironments() {}

  /**
   * Builds an environment. Nodes advertise themselves as {@code rabbitN:5552}; the resolver maps
   * each advertised host to the Toxiproxy port in front of it, so client faults apply to streams
   * too.
   *
   * @param access endpoints and credentials
   * @return a new environment, to be closed by the caller
   */
  public static Environment create(BrokerAccess access) {
    List<String> uris =
        access.streamPortByNode().keySet().stream()
            .sorted()
            .map(
                node ->
                    (access.tls().isPresent() ? "rabbitmq-stream+tls://" : "rabbitmq-stream://")
                        + node
                        + (access.tls().isPresent() ? ":5551" : ":5552"))
            .toList();
    EnvironmentBuilder builder = Environment.builder();
    access.tls().ifPresent(tls -> builder.tls().sslContext(tls.stream()));
    return builder
        .uris(uris)
        .username(access.user())
        .password(access.password())
        .virtualHost(access.vhost())
        .addressResolver(address -> resolve(access, address))
        .requestedHeartbeat(Duration.ofSeconds(5))
        .rpcTimeout(Duration.ofSeconds(5))
        .recoveryBackOffDelayPolicy(BackOffDelayPolicy.fixed(Duration.ofSeconds(1)))
        .topologyUpdateBackOffDelayPolicy(BackOffDelayPolicy.fixed(Duration.ofSeconds(1)))
        .build();
  }

  static Address resolve(BrokerAccess access, Address advertised) {
    Integer port = access.streamPortByNode().get(advertised.host());
    return port == null ? advertised : new Address(access.host(), port);
  }
}
