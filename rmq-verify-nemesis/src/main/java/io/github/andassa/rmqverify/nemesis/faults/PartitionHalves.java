package io.github.andassa.rmqverify.nemesis.faults;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.Nemesis;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Splits the cluster into a minority (the targets) and a majority that cannot talk to each other.
 */
public final class PartitionHalves implements Nemesis {

  @Override
  public String name() {
    return "partition-halves";
  }

  @Override
  public Choice choose(SplittableRandom random, List<String> nodes) {
    List<String> order = Choices.shuffled(random, nodes);
    return new Choice(List.copyOf(order.subList(0, nodes.size() / 2)), Choices.params());
  }

  @Override
  public void inject(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.cut(Choices.across(event.targets(), cluster.nodes()));
  }

  @Override
  public void heal(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.healNetwork();
  }
}
