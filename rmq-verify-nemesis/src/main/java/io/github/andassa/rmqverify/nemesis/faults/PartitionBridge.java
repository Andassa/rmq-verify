package io.github.andassa.rmqverify.nemesis.faults;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterControl.Link;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.Nemesis;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Bridge partition: two sides cannot see each other, but one bridge node sees both. With three
 * nodes {@code a - b - c}, {@code a} and {@code c} are cut and {@code b} is the bridge. Targets are
 * the bridge followed by the nodes of each side; {@code left} tells how many belong to the first.
 */
public final class PartitionBridge implements Nemesis {

  @Override
  public String name() {
    return "partition-bridge";
  }

  @Override
  public Choice choose(SplittableRandom random, List<String> nodes) {
    if (nodes.size() < 3) {
      throw new IllegalArgumentException("a bridge needs at least three nodes");
    }
    List<String> order = Choices.shuffled(random, nodes);
    int left = (order.size() - 1) / 2;
    return new Choice(order, Choices.params("left", Integer.toString(left)));
  }

  @Override
  public void inject(FaultEvent event, ClusterControl cluster) throws ClusterException {
    List<String> targets = event.targets();
    int left = event.intParam("left");
    List<String> leftSide = targets.subList(1, 1 + left);
    List<String> rightSide = targets.subList(1 + left, targets.size());
    List<Link> links = new ArrayList<>();
    for (String a : leftSide) {
      for (String b : rightSide) {
        links.add(new Link(a, b));
      }
    }
    cluster.cut(links);
  }

  @Override
  public void heal(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.healNetwork();
  }
}
