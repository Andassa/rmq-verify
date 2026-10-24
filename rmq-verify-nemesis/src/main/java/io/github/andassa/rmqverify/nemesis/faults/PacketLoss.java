package io.github.andassa.rmqverify.nemesis.faults;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.cluster.NetemSpec;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.Nemesis;
import java.util.List;
import java.util.SplittableRandom;

/** Drops 5 to 30 % of the packets of one node ({@code tc netem loss}). */
public final class PacketLoss implements Nemesis {

  @Override
  public String name() {
    return "packet-loss";
  }

  @Override
  public Choice choose(SplittableRandom random, List<String> nodes) {
    int loss = 5 + random.nextInt(26);
    return Choices.oneNode(random, nodes, Choices.params("lossPercent", Integer.toString(loss)));
  }

  @Override
  public void inject(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.netem(event.targets().get(0), new NetemSpec(0, 0, event.intParam("lossPercent")));
  }

  @Override
  public void heal(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.clearNetem(event.targets().get(0));
  }
}
