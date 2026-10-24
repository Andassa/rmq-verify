package io.github.andassa.rmqverify.cluster;

import java.util.Map;

/**
 * Refuses to touch anything that is not provably a node of the test cluster: the container must
 * exist, carry the cluster and role labels, and be attached to the dedicated, labelled network.
 * This is the last line of defence against a misconfigured topology pointing at a real system.
 */
public final class SafetyGuard {

  private final DockerCli docker;
  private final Topology topology;

  /**
   * Creates the guard.
   *
   * @param docker docker access
   * @param topology the expected cluster
   */
  public SafetyGuard(DockerCli docker, Topology topology) {
    this.docker = docker;
    this.topology = topology;
  }

  /**
   * Verifies the network and every node of the topology.
   *
   * @throws ClusterException if docker cannot be queried
   * @throws UnsafeTargetException if anything is not provably part of the test cluster
   */
  public void verifyCluster() throws ClusterException {
    Map<String, String> networkLabels = docker.networkLabels(topology.network());
    if (!topology.clusterLabel().equals(networkLabels.get(Topology.CLUSTER_LABEL))) {
      throw new UnsafeTargetException(
          "network "
              + topology.network()
              + " is not labelled as test cluster "
              + topology.clusterLabel());
    }
    for (NodeSpec node : topology.nodes()) {
      verifyNode(node.name());
    }
  }

  /**
   * Verifies one node before a fault is injected into it.
   *
   * @param nodeName logical node name
   * @return the node specification
   * @throws ClusterException if docker cannot be queried
   * @throws UnsafeTargetException if the node is unknown or not provably part of the test cluster
   */
  public NodeSpec verifyNode(String nodeName) throws ClusterException {
    NodeSpec node =
        topology
            .node(nodeName)
            .orElseThrow(
                () ->
                    new UnsafeTargetException("node " + nodeName + " is not part of the topology"));
    ContainerInfo info = docker.inspect(node.container());
    if (!topology.clusterLabel().equals(info.labels().get(Topology.CLUSTER_LABEL))
        || !"node".equals(info.labels().get(Topology.ROLE_LABEL))) {
      throw new UnsafeTargetException(
          "container " + node.container() + " lacks the test cluster labels");
    }
    if (!info.ipByNetwork().containsKey(topology.network())) {
      throw new UnsafeTargetException(
          "container " + node.container() + " is not on network " + topology.network());
    }
    return node;
  }
}
