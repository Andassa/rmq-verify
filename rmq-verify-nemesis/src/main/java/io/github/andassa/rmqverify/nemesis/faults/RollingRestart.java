package io.github.andassa.rmqverify.nemesis.faults;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.Nemesis;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Restarts every node gracefully, one at a time, waiting for the cluster to be healthy between two
 * restarts: the procedure of an upgrade. The fault lasts as long as the procedure takes, at least
 * its planned duration.
 */
public final class RollingRestart implements Nemesis {

  private static final Duration REJOIN = Duration.ofMinutes(2);

  @Override
  public String name() {
    return "rolling-restart";
  }

  @Override
  public Choice choose(SplittableRandom random, List<String> nodes) {
    return new Choice(Choices.shuffled(random, nodes), Choices.params());
  }

  @Override
  public void inject(FaultEvent event, ClusterControl cluster) throws ClusterException {
    for (String node : event.targets()) {
      cluster.stop(node);
      cluster.start(node);
      cluster.waitHealthy(REJOIN);
    }
  }

  @Override
  public void heal(FaultEvent event, ClusterControl cluster) throws ClusterException {
    for (String node : event.targets()) {
      cluster.start(node);
    }
  }
}
