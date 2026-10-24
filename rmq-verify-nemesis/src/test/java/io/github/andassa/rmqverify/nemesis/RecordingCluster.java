package io.github.andassa.rmqverify.nemesis;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.cluster.NetemSpec;
import io.github.andassa.rmqverify.cluster.UnsafeTargetException;
import io.github.andassa.rmqverify.model.Value;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** {@link ClusterControl} that records every call; selected calls can be made to fail. */
final class RecordingCluster implements ClusterControl {

  final List<String> calls = new ArrayList<>();
  Set<String> failing = Set.of();
  boolean unsafe;

  private void call(String name) throws ClusterException {
    calls.add(name);
    int space = name.indexOf(' ');
    if (failing.contains(space < 0 ? name : name.substring(0, space))) {
      throw new ClusterException(name + " failed");
    }
  }

  @Override
  public List<String> nodes() {
    return List.of("rabbit1", "rabbit2", "rabbit3");
  }

  @Override
  public void verifySafety() throws ClusterException {
    calls.add("verifySafety");
    if (unsafe) {
      throw new UnsafeTargetException("not the test cluster");
    }
  }

  @Override
  public void kill(String node) throws ClusterException {
    call("kill " + node);
  }

  @Override
  public void start(String node) throws ClusterException {
    call("start " + node);
  }

  @Override
  public void stop(String node) throws ClusterException {
    call("stop " + node);
  }

  @Override
  public void pause(String node) throws ClusterException {
    call("pause " + node);
  }

  @Override
  public void unpause(String node) throws ClusterException {
    call("unpause " + node);
  }

  @Override
  public void cut(Collection<Link> links) throws ClusterException {
    call("cut " + links.stream().map(l -> l.a() + "-" + l.b()).sorted().toList());
  }

  @Override
  public void healNetwork() throws ClusterException {
    call("healNetwork");
  }

  @Override
  public void netem(String node, NetemSpec spec) throws ClusterException {
    call("netem " + node + " " + spec.arguments());
  }

  @Override
  public void clearNetem(String node) throws ClusterException {
    call("clearNetem " + node);
  }

  @Override
  public void cutClient(String node) throws ClusterException {
    call("cutClient " + node);
  }

  @Override
  public void clientLatency(String node, long latencyMillis) throws ClusterException {
    call("clientLatency " + node + " " + latencyMillis);
  }

  @Override
  public void resetClient() throws ClusterException {
    call("resetClient");
  }

  @Override
  public void healAll() throws ClusterException {
    call("healAll");
  }

  @Override
  public void waitHealthy(Duration timeout) throws ClusterException {
    call("waitHealthy");
  }

  @Override
  public List<Value.Probe> probe(long round, List<String> queues) {
    calls.add("probe " + round);
    return List.of();
  }

  @Override
  public String brokerVersion() {
    return "4.4.0";
  }
}
