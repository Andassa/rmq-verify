package io.github.andassa.rmqverify.nemesis.faults;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.Nemesis;
import java.util.List;
import java.util.SplittableRandom;

/** {@code kill -9} of one node; healing restarts it. */
public final class KillNode implements Nemesis {

  @Override
  public String name() {
    return "kill-node";
  }

  @Override
  public Choice choose(SplittableRandom random, List<String> nodes) {
    return Choices.oneNode(random, nodes, Choices.params());
  }

  @Override
  public void inject(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.kill(event.targets().get(0));
  }

  @Override
  public void heal(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.start(event.targets().get(0));
  }
}
