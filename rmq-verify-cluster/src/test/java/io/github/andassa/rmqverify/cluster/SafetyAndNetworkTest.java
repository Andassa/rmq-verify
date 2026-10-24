package io.github.andassa.rmqverify.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SafetyAndNetworkTest {

  private final FakeDocker fake = new FakeDocker();
  private final DockerCli docker = new DockerCli(fake);
  private final SafetyGuard guard = new SafetyGuard(docker, Topology.local());

  @Test
  void acceptsTheLabelledCluster() throws Exception {
    guard.verifyCluster();
    assertThat(guard.verifyNode("rabbit2").container()).isEqualTo("rmqv-rabbit2");
  }

  @Test
  void refusesUnknownNodesWrongLabelsAndForeignNetworks() {
    assertThatThrownBy(() -> guard.verifyNode("postgres"))
        .isInstanceOf(UnsafeTargetException.class)
        .hasMessageContaining("not part of the topology");
    fake.labels.put("rmqv-rabbit3", "production");
    assertThatThrownBy(() -> guard.verifyNode("rabbit3"))
        .isInstanceOf(UnsafeTargetException.class)
        .hasMessageContaining("lacks the test cluster labels");
    assertThatThrownBy(guard::verifyCluster).isInstanceOf(UnsafeTargetException.class);
    fake.labels.clear();
    fake.networkLabel = "other";
    assertThatThrownBy(guard::verifyCluster)
        .isInstanceOf(UnsafeTargetException.class)
        .hasMessageContaining("is not labelled as test cluster");
  }

  @Test
  void refusesAContainerOutsideTheDedicatedNetwork() {
    DockerCli elsewhere =
        new DockerCli(
            (c, t) ->
                new ProcessResult(
                    0,
                    "[{\"Name\":\"/rmqv-rabbit1\",\"State\":{\"Running\":true},\"Config\":{\"Labels\":{"
                        + "\"io.github.andassa.rmq-verify.cluster\":\"rmqv\",\"io.github.andassa.rmq-verify.role\":\"node\"}},"
                        + "\"NetworkSettings\":{\"Networks\":{\"bridge\":{\"IPAddress\":\"172.17.0.2\"}}}}]",
                    ""));
    assertThatThrownBy(() -> new SafetyGuard(elsewhere, Topology.local()).verifyNode("rabbit1"))
        .isInstanceOf(UnsafeTargetException.class)
        .hasMessageContaining("is not on network rmqv-net");
  }

  @Test
  void partitionRulesLiveInADedicatedChain() throws Exception {
    fake.execHandler =
        c ->
            c.contains("-N")
                ? new ProcessResult(1, "", "iptables: Chain already exists.")
                : c.contains("-C")
                    ? new ProcessResult(1, "", "no rule")
                    : new ProcessResult(0, "", "");
    NetworkFaults network = new NetworkFaults(docker);
    network.block("rmqv-rabbit1", List.of("172.20.0.12"));
    network.heal("rmqv-rabbit1");
    assertThat(fake.execs())
        .extracting(c -> String.join(" ", c.subList(4, c.size())))
        .containsExactly(
            "rmqv-rabbit1 iptables -w -N RMQV",
            "rmqv-rabbit1 iptables -w -C INPUT -j RMQV",
            "rmqv-rabbit1 iptables -w -I INPUT 1 -j RMQV",
            "rmqv-rabbit1 iptables -w -C OUTPUT -j RMQV",
            "rmqv-rabbit1 iptables -w -I OUTPUT 1 -j RMQV",
            "rmqv-rabbit1 iptables -w -A RMQV -s 172.20.0.12 -j DROP",
            "rmqv-rabbit1 iptables -w -A RMQV -d 172.20.0.12 -j DROP",
            "rmqv-rabbit1 iptables -w -N RMQV",
            "rmqv-rabbit1 iptables -w -C INPUT -j RMQV",
            "rmqv-rabbit1 iptables -w -I INPUT 1 -j RMQV",
            "rmqv-rabbit1 iptables -w -C OUTPUT -j RMQV",
            "rmqv-rabbit1 iptables -w -I OUTPUT 1 -j RMQV",
            "rmqv-rabbit1 iptables -w -F RMQV");
  }

  @Test
  void iptablesFailuresAndBadAddressesAreReported() {
    fake.execHandler = c -> new ProcessResult(4, "", "Permission denied (you must be root)");
    NetworkFaults network = new NetworkFaults(docker);
    assertThatThrownBy(() -> network.heal("rmqv-rabbit1"))
        .hasMessageContaining("Permission denied");
    fake.execHandler = c -> new ProcessResult(0, "", "");
    assertThatThrownBy(() -> network.block("rmqv-rabbit1", List.of("0.0.0.0/0")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NetworkFaults.requireIpv4(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void netemIsInstalledAndClearedIdempotently() throws Exception {
    NetworkFaults network = new NetworkFaults(docker);
    network.netem("rmqv-rabbit2", new NetemSpec(200, 50, 10));
    fake.execHandler =
        c -> new ProcessResult(2, "", "Error: Cannot delete qdisc with handle of zero.");
    network.clearNetem("rmqv-rabbit2");
    fake.execHandler =
        c -> new ProcessResult(2, "", "RTNETLINK answers: No such file or directory");
    network.clearNetem("rmqv-rabbit2");
    fake.execHandler = c -> new ProcessResult(1, "", "Operation not permitted");
    assertThatThrownBy(() -> network.clearNetem("rmqv-rabbit2"))
        .hasMessageContaining("Operation not permitted");
    assertThatThrownBy(() -> network.netem("rmqv-rabbit2", new NetemSpec(10, 0, 0)))
        .hasMessageContaining("Operation not permitted");
    assertThat(fake.execs().get(0))
        .containsSubsequence(
            "tc", "qdisc", "replace", "dev", "eth0", "root", "netem", "delay", "200ms", "50ms",
            "loss", "10.00%");
  }

  @Test
  void netemSpecValidation() {
    assertThat(new NetemSpec(0, 0, 5).arguments()).containsExactly("loss", "5.00%");
    assertThat(new NetemSpec(100, 0, 0).arguments()).containsExactly("delay", "100ms", "0ms");
    assertThatThrownBy(() -> new NetemSpec(0, 0, 0)).hasMessageContaining("no effect");
    assertThatThrownBy(() -> new NetemSpec(10, 20, 0)).hasMessageContaining("delay/jitter");
    assertThatThrownBy(() -> new NetemSpec(-1, 0, 0)).hasMessageContaining("delay/jitter");
    assertThatThrownBy(() -> new NetemSpec(60_001, 0, 0)).hasMessageContaining("delay/jitter");
    assertThatThrownBy(() -> new NetemSpec(10, 0, 101)).hasMessageContaining("loss");
    assertThatThrownBy(() -> new NetemSpec(10, 0, -1)).hasMessageContaining("loss");
    assertThatThrownBy(() -> new NetemSpec(10, 0, Double.NaN)).hasMessageContaining("loss");
  }
}
