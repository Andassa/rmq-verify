package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.cluster.NetemSpec;
import io.github.andassa.rmqverify.model.Value;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Cluster that accepts every command, records it and reports a consistent view in probes. */
final class FakeCluster implements ClusterControl {

  final List<String> calls = new ArrayList<>();
  boolean unhealthy;

  @Override
  public List<String> nodes() {
    return List.of("rabbit1", "rabbit2", "rabbit3");
  }

  @Override
  public void verifySafety() {
    calls.add("verifySafety");
  }

  @Override
  public void kill(String node) {
    calls.add("kill " + node);
  }

  @Override
  public void start(String node) {
    calls.add("start " + node);
  }

  @Override
  public void stop(String node) {
    calls.add("stop " + node);
  }

  @Override
  public void pause(String node) {
    calls.add("pause " + node);
  }

  @Override
  public void unpause(String node) {
    calls.add("unpause " + node);
  }

  @Override
  public void cut(Collection<Link> links) {
    calls.add("cut");
  }

  @Override
  public void healNetwork() {
    calls.add("healNetwork");
  }

  @Override
  public void netem(String node, NetemSpec spec) {
    calls.add("netem " + node);
  }

  @Override
  public void clearNetem(String node) {
    calls.add("clearNetem " + node);
  }

  @Override
  public void cutClient(String node) {
    calls.add("cutClient " + node);
  }

  @Override
  public void clientLatency(String node, long latencyMillis) {
    calls.add("clientLatency " + node);
  }

  @Override
  public void resetClient() {
    calls.add("resetClient");
  }

  @Override
  public void healAll() {
    calls.add("healAll");
  }

  @Override
  public void waitHealthy(Duration timeout) throws ClusterException {
    calls.add("waitHealthy");
    if (unhealthy) {
      throw new ClusterException("cluster not healthy");
    }
  }

  @Override
  public List<Value.Probe> probe(long round, List<String> queues) {
    List<Value.Probe> probes = new ArrayList<>();
    for (String node : nodes()) {
      probes.add(new Value.Probe(round, node, "cluster", "", nodes(), List.of()));
      for (String queue : queues) {
        probes.add(new Value.Probe(round, node, queue, "rabbit1", nodes(), List.of()));
      }
    }
    return probes;
  }

  @Override
  public String brokerVersion() {
    return "4.4.0";
  }
}
