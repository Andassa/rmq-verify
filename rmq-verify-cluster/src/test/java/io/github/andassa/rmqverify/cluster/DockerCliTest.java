package io.github.andassa.rmqverify.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DockerCliTest {

  private final FakeDocker fake = new FakeDocker();
  private final DockerCli docker = new DockerCli(fake);

  @Test
  void inspectParsesStateLabelsAndAddresses() throws Exception {
    ContainerInfo info = docker.inspect("rmqv-rabbit2");
    assertThat(info.name()).isEqualTo("rmqv-rabbit2");
    assertThat(info.running()).isTrue();
    assertThat(info.paused()).isFalse();
    assertThat(info.labels()).containsEntry(Topology.ROLE_LABEL, "node");
    assertThat(info.ipByNetwork()).containsEntry("rmqv-net", "172.20.0.12");
    assertThat(fake.commands.get(0))
        .containsExactly("docker", "inspect", "--type", "container", "rmqv-rabbit2");
  }

  @Test
  void lifecycleCommandsAreStructured() throws Exception {
    docker.kill("rmqv-rabbit1");
    docker.start("rmqv-rabbit1");
    docker.stop("rmqv-rabbit1");
    docker.pause("rmqv-rabbit3");
    docker.unpause("rmqv-rabbit3");
    assertThat(fake.commands)
        .containsExactly(
            List.of("docker", "kill", "--signal", "KILL", "rmqv-rabbit1"),
            List.of("docker", "start", "rmqv-rabbit1"),
            List.of("docker", "stop", "--time", "30", "rmqv-rabbit1"),
            List.of("docker", "pause", "rmqv-rabbit3"),
            List.of("docker", "unpause", "rmqv-rabbit3"));
  }

  @Test
  void execAsRootAddsTheUserFlag() throws Exception {
    docker.exec("rmqv-rabbit1", true, List.of("tc", "qdisc", "show"));
    docker.exec("rmqv-rabbit1", false, List.of("id"));
    assertThat(fake.commands)
        .containsExactly(
            List.of("docker", "exec", "--user", "0", "rmqv-rabbit1", "tc", "qdisc", "show"),
            List.of("docker", "exec", "rmqv-rabbit1", "id"));
  }

  @Test
  void failuresCarryTheCommandAndStderr() {
    assertThatThrownBy(() -> docker.kill("rmqv-rabbit9"))
        .isInstanceOf(ClusterException.class)
        .hasMessageContaining(
            "docker kill --signal KILL rmqv-rabbit9 failed (1): No such container");
    fake.networkLabel = null;
    assertThatThrownBy(() -> docker.networkLabels("rmqv-net"))
        .hasMessageContaining("no such network");
  }

  @Test
  void unsafeNamesNeverReachTheRunner() {
    for (String name : List.of("--privileged", "a;rm -rf /", "", "x".repeat(64), "$(id)")) {
      assertThatThrownBy(() -> docker.kill(name)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> docker.inspect(name)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> docker.exec(name, true, List.of("id")))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> docker.networkLabels("-net"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(fake.commands).isEmpty();
  }

  @Test
  void unreadableOutputIsReported() {
    DockerCli broken = new DockerCli((c, t) -> new ProcessResult(0, "not json", ""));
    assertThatThrownBy(() -> broken.inspect("rmqv-rabbit1"))
        .hasMessageContaining("unreadable docker output");
  }

  @Test
  void networkLabelsAreRead() throws Exception {
    assertThat(docker.networkLabels("rmqv-net")).containsEntry(Topology.CLUSTER_LABEL, "rmqv");
  }
}
