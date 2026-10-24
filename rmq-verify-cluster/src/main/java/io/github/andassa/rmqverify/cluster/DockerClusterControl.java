package io.github.andassa.rmqverify.cluster;

import io.github.andassa.rmqverify.model.Value;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ClusterControl} over Docker, iptables, tc and Toxiproxy, guarded by {@link SafetyGuard}.
 */
public final class DockerClusterControl implements ClusterControl {

  private static final Logger LOG = LoggerFactory.getLogger(DockerClusterControl.class);
  private static final String CLUSTER_SUBJECT = "cluster";

  private final Topology topology;
  private final DockerCli docker;
  private final SafetyGuard guard;
  private final NetworkFaults network;
  private final ToxiproxyClient toxiproxy;
  private final Map<String, ManagementClient> management = new LinkedHashMap<>();

  /**
   * Creates the controller.
   *
   * @param topology the cluster
   * @param docker docker access
   * @param toxiproxy Toxiproxy access
   * @param admin management credentials
   */
  public DockerClusterControl(
      Topology topology, DockerCli docker, ToxiproxyClient toxiproxy, Credentials admin) {
    this.topology = topology;
    this.docker = docker;
    this.guard = new SafetyGuard(docker, topology);
    this.network = new NetworkFaults(docker);
    this.toxiproxy = toxiproxy;
    for (NodeSpec node : topology.nodes()) {
      management.put(
          node.name(), new ManagementClient(node.management(), admin, Duration.ofSeconds(5)));
    }
  }

  @Override
  public List<String> nodes() {
    return topology.nodeNames();
  }

  @Override
  public void verifySafety() throws ClusterException {
    guard.verifyCluster();
  }

  @Override
  public void kill(String node) throws ClusterException {
    docker.kill(guard.verifyNode(node).container());
  }

  @Override
  public void start(String node) throws ClusterException {
    NodeSpec spec = guard.verifyNode(node);
    if (!docker.inspect(spec.container()).running()) {
      docker.start(spec.container());
    }
  }

  @Override
  public void stop(String node) throws ClusterException {
    docker.stop(guard.verifyNode(node).container());
  }

  @Override
  public void pause(String node) throws ClusterException {
    docker.pause(guard.verifyNode(node).container());
  }

  @Override
  public void unpause(String node) throws ClusterException {
    NodeSpec spec = guard.verifyNode(node);
    if (docker.inspect(spec.container()).paused()) {
      docker.unpause(spec.container());
    }
  }

  @Override
  public void cut(Collection<Link> links) throws ClusterException {
    Map<String, String> addresses = new LinkedHashMap<>();
    for (NodeSpec node : topology.nodes()) {
      guard.verifyNode(node.name());
      addresses.put(
          node.name(), docker.inspect(node.container()).ipByNetwork().get(topology.network()));
    }
    for (Link link : links) {
      String a = guard.verifyNode(link.a()).container();
      String b = guard.verifyNode(link.b()).container();
      network.block(a, List.of(addresses.get(link.b())));
      network.block(b, List.of(addresses.get(link.a())));
    }
  }

  @Override
  public void healNetwork() throws ClusterException {
    for (NodeSpec node : topology.nodes()) {
      if (docker.inspect(guard.verifyNode(node.name()).container()).running()) {
        network.heal(node.container());
      }
    }
  }

  @Override
  public void netem(String node, NetemSpec spec) throws ClusterException {
    network.netem(guard.verifyNode(node).container(), spec);
  }

  @Override
  public void clearNetem(String node) throws ClusterException {
    NodeSpec spec = guard.verifyNode(node);
    if (docker.inspect(spec.container()).running()) {
      network.clearNetem(spec.container());
    }
  }

  @Override
  public void cutClient(String node) throws ClusterException {
    NodeSpec spec = guard.verifyNode(node);
    toxiproxy.setEnabled(spec.amqpProxy(), false);
    toxiproxy.setEnabled(spec.streamProxy(), false);
  }

  @Override
  public void clientLatency(String node, long latencyMillis) throws ClusterException {
    NodeSpec spec = guard.verifyNode(node);
    Map<String, Long> attributes = Map.of("latency", latencyMillis, "jitter", latencyMillis / 4);
    toxiproxy.addToxic(spec.amqpProxy(), "rmqv-latency", "latency", attributes);
    toxiproxy.addToxic(spec.streamProxy(), "rmqv-latency", "latency", attributes);
  }

  @Override
  public void resetClient() throws ClusterException {
    toxiproxy.reset();
  }

  @Override
  public void healAll() throws ClusterException {
    List<ClusterException> failures = new ArrayList<>();
    for (String node : nodes()) {
      attempt(failures, () -> unpause(node));
      attempt(failures, () -> start(node));
    }
    attempt(failures, this::healNetwork);
    for (String node : nodes()) {
      attempt(failures, () -> clearNetem(node));
    }
    attempt(failures, this::resetClient);
    if (!failures.isEmpty()) {
      ClusterException aggregate =
          new ClusterException(
              failures.size() + " heal step(s) failed: " + failures.get(0).getMessage());
      failures.forEach(aggregate::addSuppressed);
      throw aggregate;
    }
  }

  private static void attempt(List<ClusterException> failures, Step step) {
    try {
      step.run();
    } catch (ClusterException e) {
      LOG.error("heal step failed: {}", Logs.safe(e.getMessage()));
      failures.add(e);
    }
  }

  @Override
  public void waitHealthy(Duration timeout) throws ClusterException {
    Instant deadline = Instant.now().plus(timeout);
    String last = "no answer yet";
    while (Instant.now().isBefore(deadline)) {
      Optional<String> problem = healthProblem();
      if (problem.isEmpty()) {
        return;
      }
      last = problem.get();
      sleep(Duration.ofSeconds(1));
    }
    throw new ClusterException("cluster not healthy after " + timeout + ": " + last);
  }

  private Optional<String> healthProblem() {
    TreeSet<String> expected = new TreeSet<>(nodes());
    for (Map.Entry<String, ManagementClient> entry : management.entrySet()) {
      try {
        List<NodeView> views = entry.getValue().nodes();
        TreeSet<String> running = new TreeSet<>();
        for (NodeView view : views) {
          if (!view.partitions().isEmpty()) {
            return Optional.of(view.name() + " reports partitions " + view.partitions());
          }
          if (view.running()) {
            running.add(view.name());
          }
        }
        if (!running.equals(expected)) {
          return Optional.of(entry.getKey() + " sees running " + running);
        }
      } catch (ClusterException e) {
        return Optional.of(entry.getKey() + ": " + e.getMessage());
      }
    }
    return Optional.empty();
  }

  @Override
  public List<Value.Probe> probe(long round, List<String> queues) {
    List<Value.Probe> probes = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<List<Value.Probe>>> futures = new ArrayList<>();
      for (Map.Entry<String, ManagementClient> entry : management.entrySet()) {
        futures.add(
            executor.submit(() -> probeNode(round, entry.getKey(), entry.getValue(), queues)));
      }
      for (Future<List<Value.Probe>> future : futures) {
        probes.addAll(future.get());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      LOG.warn("probe round {} incomplete: {}", round, Logs.safe(e.getMessage()));
    }
    return probes;
  }

  private List<Value.Probe> probeNode(
      long round, String observer, ManagementClient client, List<String> queues) {
    List<Value.Probe> probes = new ArrayList<>();
    try {
      List<NodeView> views = client.nodes();
      List<String> running =
          views.stream().filter(NodeView::running).map(NodeView::name).sorted().toList();
      List<String> partitions =
          views.stream()
              .filter(v -> v.name().equals(observer))
              .flatMap(v -> v.partitions().stream())
              .sorted()
              .toList();
      probes.add(new Value.Probe(round, observer, CLUSTER_SUBJECT, "", running, partitions));
      for (String queue : queues) {
        Optional<QueueView> view = client.queue(topology.vhost(), queue);
        probes.add(
            new Value.Probe(
                round,
                observer,
                queue,
                view.map(QueueView::leader).orElse(""),
                running,
                partitions));
      }
    } catch (ClusterException e) {
      LOG.debug("probe of {} skipped: {}", Logs.safe(observer), Logs.safe(e.getMessage()));
    }
    return probes;
  }

  @Override
  public String brokerVersion() throws ClusterException {
    ClusterException last = null;
    for (ManagementClient client : management.values()) {
      try {
        return client.version();
      } catch (ClusterException e) {
        last = e;
      }
    }
    throw new ClusterException("no node reports a version", last);
  }

  private static void sleep(Duration duration) throws ClusterException {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ClusterException("interrupted while waiting", e);
    }
  }

  /** A heal step that may fail. */
  @FunctionalInterface
  private interface Step {
    void run() throws ClusterException;
  }
}
