package io.github.andassa.rmqverify.nemesis.faults;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.Nemesis;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Faults between clients and one broker through Toxiproxy: either the connections are cut (the
 * proxy is disabled) or 1 to 3 seconds of latency are added, which makes confirms time out.
 */
public final class ClientToxic implements Nemesis {

  @Override
  public String name() {
    return "client-toxic";
  }

  @Override
  public Choice choose(SplittableRandom random, List<String> nodes) {
    boolean cut = random.nextBoolean();
    String latency = Integer.toString(1000 + random.nextInt(2001));
    return Choices.oneNode(
        random, nodes, Choices.params("mode", cut ? "cut" : "latency", "latencyMs", latency));
  }

  @Override
  public void inject(FaultEvent event, ClusterControl cluster) throws ClusterException {
    String node = event.targets().get(0);
    if ("cut".equals(event.params().get("mode"))) {
      cluster.cutClient(node);
    } else {
      cluster.clientLatency(node, event.intParam("latencyMs"));
    }
  }

  @Override
  public void heal(FaultEvent event, ClusterControl cluster) throws ClusterException {
    cluster.resetClient();
  }
}
