package io.github.andassa.rmqverify.cluster;

import java.util.ArrayList;
import java.util.List;

/**
 * Network faults between nodes, applied inside the node containers. Partition rules live in a
 * dedicated {@code RMQV} chain so that healing flushes only what rmq-verify added.
 */
public final class NetworkFaults {

  static final String CHAIN = "RMQV";
  private static final String DEVICE = "eth0";

  private final DockerCli docker;

  /**
   * Creates the fault injector.
   *
   * @param docker docker access
   */
  public NetworkFaults(DockerCli docker) {
    this.docker = docker;
  }

  /**
   * Drops every packet between a container and a set of peer addresses, in both directions.
   *
   * @param container container that receives the rules
   * @param peerAddresses IPv4 addresses to cut off
   * @throws ClusterException if a rule cannot be installed
   */
  public void block(String container, List<String> peerAddresses) throws ClusterException {
    ensureChain(container);
    for (String address : peerAddresses) {
      requireIpv4(address);
      iptables(container, List.of("-A", CHAIN, "-s", address, "-j", "DROP"));
      iptables(container, List.of("-A", CHAIN, "-d", address, "-j", "DROP"));
    }
  }

  /**
   * Removes every partition rule from a container. Safe to call when none exists.
   *
   * @param container the container
   * @throws ClusterException if the chain cannot be flushed
   */
  public void heal(String container) throws ClusterException {
    ensureChain(container);
    iptables(container, List.of("-F", CHAIN));
  }

  /**
   * Installs a netem discipline on the container's interface, replacing any previous one.
   *
   * @param container the container
   * @param spec delay, jitter and loss
   * @throws ClusterException if tc fails
   */
  public void netem(String container, NetemSpec spec) throws ClusterException {
    List<String> command =
        new ArrayList<>(List.of("tc", "qdisc", "replace", "dev", DEVICE, "root", "netem"));
    command.addAll(spec.arguments());
    require(docker.exec(container, true, command), command);
  }

  /**
   * Removes the netem discipline. Safe to call when none is installed.
   *
   * @param container the container
   * @throws ClusterException if tc fails for another reason than an absent discipline
   */
  public void clearNetem(String container) throws ClusterException {
    List<String> command = List.of("tc", "qdisc", "del", "dev", DEVICE, "root");
    ProcessResult result = docker.exec(container, true, command);
    if (!result.succeeded()
        && !result.stderr().contains("No such file")
        && !result.stderr().contains("Cannot delete")) {
      require(result, command);
    }
  }

  private void ensureChain(String container) throws ClusterException {
    ProcessResult created = docker.exec(container, true, List.of("iptables", "-w", "-N", CHAIN));
    if (!created.succeeded() && !created.stderr().contains("exists")) {
      require(created, List.of("iptables", "-N", CHAIN));
    }
    for (String hook : List.of("INPUT", "OUTPUT")) {
      ProcessResult present =
          docker.exec(container, true, List.of("iptables", "-w", "-C", hook, "-j", CHAIN));
      if (!present.succeeded()) {
        iptables(container, List.of("-I", hook, "1", "-j", CHAIN));
      }
    }
  }

  private void iptables(String container, List<String> arguments) throws ClusterException {
    List<String> command = new ArrayList<>(List.of("iptables", "-w"));
    command.addAll(arguments);
    require(docker.exec(container, true, command), command);
  }

  private static void require(ProcessResult result, List<String> command) throws ClusterException {
    if (!result.succeeded()) {
      throw new ClusterException(String.join(" ", command) + " failed: " + result.stderr().strip());
    }
  }

  static void requireIpv4(String address) {
    if (address == null || !address.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
      throw new IllegalArgumentException("not an IPv4 address: " + address);
    }
  }
}
