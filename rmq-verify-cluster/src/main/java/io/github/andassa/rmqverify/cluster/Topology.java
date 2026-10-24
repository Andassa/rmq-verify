package io.github.andassa.rmqverify.cluster;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Shape of the test cluster as started by {@code deploy/docker-compose.yml}.
 *
 * @param clusterLabel value of the {@code io.github.andassa.rmq-verify.cluster} label
 * @param network dedicated Docker network of the cluster
 * @param host address where the published ports are reachable
 * @param vhost virtual host used by the workload
 * @param toxiproxy base URI of the Toxiproxy API
 * @param nodes the RabbitMQ nodes
 */
public record Topology(
    String clusterLabel,
    String network,
    String host,
    String vhost,
    URI toxiproxy,
    List<NodeSpec> nodes) {

  /** Label key that marks every container and network of a test cluster. */
  public static final String CLUSTER_LABEL = "io.github.andassa.rmq-verify.cluster";

  /** Label key that marks the role of a container. */
  public static final String ROLE_LABEL = "io.github.andassa.rmq-verify.role";

  /** Validates names and copies the node list. */
  public Topology {
    NodeSpec.requireSafe(clusterLabel, "clusterLabel");
    NodeSpec.requireSafe(network, "network");
    NodeSpec.requireSafe(host, "host");
    NodeSpec.requireSafe(vhost, "vhost");
    if (toxiproxy == null) {
      throw new IllegalArgumentException("toxiproxy must not be null");
    }
    if (nodes == null || nodes.isEmpty()) {
      throw new IllegalArgumentException("at least one node is required");
    }
    nodes = List.copyOf(nodes);
    if (nodes.stream().map(NodeSpec::name).distinct().count() != nodes.size()) {
      throw new IllegalArgumentException("node names must be unique");
    }
  }

  /**
   * Returns the topology of {@code deploy/docker-compose.yml} on the local machine.
   *
   * @return the default local topology
   */
  public static Topology local() {
    List<NodeSpec> nodes =
        List.of(
            new NodeSpec(
                "rabbit1", "rmqv-rabbit1", URI.create("http://127.0.0.1:15672"), 25671, 25551),
            new NodeSpec(
                "rabbit2", "rmqv-rabbit2", URI.create("http://127.0.0.1:15673"), 25672, 25552),
            new NodeSpec(
                "rabbit3", "rmqv-rabbit3", URI.create("http://127.0.0.1:15674"), 25673, 25553));
    return new Topology(
        "rmqv", "rmqv-net", "127.0.0.1", "rmqv", URI.create("http://127.0.0.1:8474"), nodes);
  }

  /**
   * Looks a node up by logical name.
   *
   * @param name node name
   * @return the node, empty if it is not part of the topology
   */
  public Optional<NodeSpec> node(String name) {
    return nodes.stream().filter(n -> n.name().equals(name)).findFirst();
  }

  /**
   * Returns the logical node names.
   *
   * @return names in topology order
   */
  public List<String> nodeNames() {
    return nodes.stream().map(NodeSpec::name).toList();
  }
}
