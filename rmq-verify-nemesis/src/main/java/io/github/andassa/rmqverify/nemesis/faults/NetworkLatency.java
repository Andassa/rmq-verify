package io.github.andassa.rmqverify.nemesis.faults;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.cluster.NetemSpec;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.Nemesis;
import java.util.List;
import java.util.SplittableRandom;

/** Adds 100 to 500 ms of delay, with jitter, on one node's interface ({@code tc netem}). */
public final class NetworkLatency implements Nemesis {

  @Override
  public String name() {
    return "network-latency";
  }

  @Override
  public Choice choose(SplittableRandom random, List<String> nodes) {
    int delay = 100 + random.nextInt(401);
    return Choices.oneNode(
        random,
        nodes,
        Choices.params(
            "delayMs", Integer.toString(delay), "jitterMs", Integer.toString(delay / 4)));
  }

  @Override
  public void inject(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.netem(
        event.targets().get(0),
        new NetemSpec(event.intParam("delayMs"), event.intParam("jitterMs"), 0));
  }

  @Override
  public void heal(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.clearNetem(event.targets().get(0));
  }
}
