package io.github.andassa.rmqverify.cluster;

import io.github.andassa.rmqverify.model.Value;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Port through which the nemesis acts on the cluster. Every injecting method verifies its targets
 * with the safety guard first; every healing method is idempotent.
 */
public interface ClusterControl {

  /**
   * Returns the logical node names.
   *
   * @return names in topology order
   */
  List<String> nodes();

  /**
   * Verifies that the whole topology is the labelled test cluster.
   *
   * @throws ClusterException if verification fails or the cluster is not the expected one
   */
  void verifySafety() throws ClusterException;

  /**
   * Kills a node with {@code SIGKILL}.
   *
   * @param node node name
   * @throws ClusterException on failure
   */
  void kill(String node) throws ClusterException;

  /**
   * Starts a stopped or killed node.
   *
   * @param node node name
   * @throws ClusterException on failure
   */
  void start(String node) throws ClusterException;

  /**
   * Stops a node gracefully.
   *
   * @param node node name
   * @throws ClusterException on failure
   */
  void stop(String node) throws ClusterException;

  /**
   * Freezes a node.
   *
   * @param node node name
   * @throws ClusterException on failure
   */
  void pause(String node) throws ClusterException;

  /**
   * Resumes a frozen node.
   *
   * @param node node name
   * @throws ClusterException on failure
   */
  void unpause(String node) throws ClusterException;

  /**
   * Cuts the inter-node links given as pairs; traffic between the two nodes of each pair is dropped
   * in both directions. Clients still reach every node through Toxiproxy.
   *
   * @param links pairs of node names
   * @throws ClusterException on failure
   */
  void cut(Collection<Link> links) throws ClusterException;

  /**
   * Removes every partition rule from every node.
   *
   * @throws ClusterException on failure
   */
  void healNetwork() throws ClusterException;

  /**
   * Adds latency and loss on a node's interface.
   *
   * @param node node name
   * @param spec netem parameters
   * @throws ClusterException on failure
   */
  void netem(String node, NetemSpec spec) throws ClusterException;

  /**
   * Removes netem from a node.
   *
   * @param node node name
   * @throws ClusterException on failure
   */
  void clearNetem(String node) throws ClusterException;

  /**
   * Disables the Toxiproxy proxies of a node: clients lose their connections to it.
   *
   * @param node node name
   * @throws ClusterException on failure
   */
  void cutClient(String node) throws ClusterException;

  /**
   * Adds latency between clients and a node.
   *
   * @param node node name
   * @param latencyMillis added latency
   * @throws ClusterException on failure
   */
  void clientLatency(String node, long latencyMillis) throws ClusterException;

  /**
   * Restores every client path.
   *
   * @throws ClusterException on failure
   */
  void resetClient() throws ClusterException;

  /**
   * Undoes every possible fault: unpauses, starts, heals the network, clears netem and resets
   * Toxiproxy. Attempts every step even if some fail.
   *
   * @throws ClusterException if at least one step failed, with the others as suppressed exceptions
   */
  void healAll() throws ClusterException;

  /**
   * Waits until every node runs, sees the others and reports no partition.
   *
   * @param timeout maximum wait
   * @throws ClusterException if the cluster is not healthy in time
   */
  void waitHealthy(Duration timeout) throws ClusterException;

  /**
   * Takes one probe round: every reachable node reports its view of the cluster and of each queue.
   *
   * @param round round number
   * @param queues queues whose leader is asked for
   * @return the probes; unreachable nodes are absent
   */
  List<Value.Probe> probe(long round, List<String> queues);

  /**
   * Returns the broker version.
   *
   * @return the version reported by the first reachable node
   * @throws ClusterException if no node answers
   */
  String brokerVersion() throws ClusterException;

  /**
   * An undirected link between two nodes.
   *
   * @param a first node
   * @param b second node
   */
  record Link(String a, String b) {
    /** Rejects self-links. */
    public Link {
      if (a.equals(b)) {
        throw new IllegalArgumentException("a link needs two distinct nodes: " + a);
      }
    }
  }
}
