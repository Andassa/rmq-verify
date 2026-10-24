package io.github.andassa.rmqverify.nemesis.faults;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.Nemesis;
import java.util.List;
import java.util.SplittableRandom;

/** Freezes one node ({@code docker pause}): a process that stops answering without dying. */
public final class PauseNode implements Nemesis {

  @Override
  public String name() {
    return "pause-node";
  }

  @Override
  public Choice choose(SplittableRandom random, List<String> nodes) {
    return Choices.oneNode(random, nodes, Choices.params());
  }

  @Override
  public void inject(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.pause(event.targets().get(0));
  }

  @Override
  public void heal(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.unpause(event.targets().get(0));
  }
}
