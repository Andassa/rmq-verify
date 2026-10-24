package io.github.andassa.rmqverify.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.model.Value;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DockerClusterControlTest {

  private static final String HEALTHY =
      "[{\"name\":\"rabbit@rabbit1\",\"running\":true,\"partitions\":[]},"
          + "{\"name\":\"rabbit@rabbit2\",\"running\":true,\"partitions\":[]},"
          + "{\"name\":\"rabbit@rabbit3\",\"running\":true,\"partitions\":[]}]";

  private FakeHttp http;
  private FakeDocker docker;
  private DockerClusterControl control;

  @BeforeEach
  void setUp() throws Exception {
    http = new FakeHttp();
    docker = new FakeDocker();
    URI base = http.uri();
    Topology topology =
        new Topology(
            "rmqv",
            "rmqv-net",
            "127.0.0.1",
            "rmqv",
            base,
            List.of(
                new NodeSpec("rabbit1", "rmqv-rabbit1", base, 25671, 25551),
                new NodeSpec("rabbit2", "rmqv-rabbit2", base, 25672, 25552),
                new NodeSpec("rabbit3", "rmqv-rabbit3", base, 25673, 25553)));
    control =
        new DockerClusterControl(
            topology,
            new DockerCli(docker),
            new ToxiproxyClient(base),
            new Credentials("admin", "pw"));
    http.responses.put("/api/nodes", HEALTHY);
    http.responses.put("/api/overview", "{\"rabbitmq_version\":\"3.13.7\"}");
  }

  @AfterEach
  void tearDown() {
    http.close();
  }

  @Test
  void lifecycleGoesThroughTheGuard() throws Exception {
    control.verifySafety();
    control.kill("rabbit1");
    assertThat(docker.running.get("rmqv-rabbit1")).isFalse();
    control.start("rabbit1");
    control.start("rabbit1");
    assertThat(docker.running.get("rmqv-rabbit1")).isTrue();
    control.pause("rabbit2");
    control.unpause("rabbit2");
    control.unpause("rabbit2");
    control.stop("rabbit3");
    assertThat(docker.commands).filteredOn(c -> c.get(1).equals("start")).hasSize(1);
    assertThat(docker.commands).filteredOn(c -> c.get(1).equals("unpause")).hasSize(1);
    assertThatThrownBy(() -> control.kill("rabbit9")).isInstanceOf(UnsafeTargetException.class);
    assertThat(control.nodes()).containsExactly("rabbit1", "rabbit2", "rabbit3");
  }

  @Test
  void cutBlocksBothDirectionsByAddress() throws Exception {
    control.cut(List.of(new ClusterControl.Link("rabbit1", "rabbit3")));
    List<String> rules =
        docker.execs().stream()
            .map(c -> String.join(" ", c.subList(4, c.size())))
            .filter(c -> c.contains("DROP"))
            .toList();
    assertThat(rules)
        .containsExactly(
            "rmqv-rabbit1 iptables -w -A RMQV -s 172.20.0.13 -j DROP",
            "rmqv-rabbit1 iptables -w -A RMQV -d 172.20.0.13 -j DROP",
            "rmqv-rabbit3 iptables -w -A RMQV -s 172.20.0.11 -j DROP",
            "rmqv-rabbit3 iptables -w -A RMQV -d 172.20.0.11 -j DROP");
    assertThatThrownBy(() -> new ClusterControl.Link("rabbit1", "rabbit1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void healAllUndoesEverythingAndSkipsStoppedNodesForNetworkSteps() throws Exception {
    control.kill("rabbit2");
    control.pause("rabbit3");
    control.netem("rabbit1", new NetemSpec(100, 10, 0));
    control.cutClient("rabbit1");
    control.clientLatency("rabbit2", 400);
    control.healAll();
    assertThat(docker.running.values()).containsOnly(true);
    assertThat(docker.paused.values()).containsOnly(false);
    assertThat(http.requests())
        .extracting(FakeHttp.Request::path)
        .contains(
            "/proxies/amqp-rabbit1",
            "/proxies/stream-rabbit1",
            "/proxies/amqp-rabbit2/toxics",
            "/proxies/stream-rabbit2/toxics",
            "/reset");
  }

  @Test
  void healAllAttemptsEveryStepAndAggregatesFailures() {
    docker.execHandler = c -> new ProcessResult(1, "", "boom");
    http.statuses.put("/reset", 500);
    assertThatThrownBy(control::healAll)
        .isInstanceOf(ClusterException.class)
        .hasMessageContaining("heal step(s) failed")
        .satisfies(e -> assertThat(e.getSuppressed()).hasSizeGreaterThanOrEqualTo(5));
  }

  @Test
  void waitHealthyReturnsWhenEveryNodeAgrees() throws Exception {
    control.waitHealthy(Duration.ofSeconds(5));
    assertThat(control.brokerVersion()).isEqualTo("3.13.7");
  }

  @Test
  void waitHealthyTimesOutWithTheLastProblem() {
    http.responses.put(
        "/api/nodes",
        "[{\"name\":\"rabbit@rabbit1\",\"running\":true,\"partitions\":[\"rabbit@rabbit2\"]}]");
    assertThatThrownBy(() -> control.waitHealthy(Duration.ofMillis(1500)))
        .hasMessageContaining("rabbit1 reports partitions [rabbit2]");
    http.responses.put(
        "/api/nodes", "[{\"name\":\"rabbit@rabbit1\",\"running\":true,\"partitions\":[]}]");
    assertThatThrownBy(() -> control.waitHealthy(Duration.ofMillis(1500)))
        .hasMessageContaining("sees running [rabbit1]");
    http.statuses.put("/api/nodes", 503);
    assertThatThrownBy(() -> control.waitHealthy(Duration.ofMillis(1500)))
        .hasMessageContaining("returned 503");
  }

  @Test
  void probeRecordsEachNodeView() {
    http.responses.put(
        "/api/queues/rmqv/rmqv.q", "{\"name\":\"rmqv.q\",\"leader\":\"rabbit@rabbit2\"}");
    List<Value.Probe> probes = control.probe(4, List.of("rmqv.q"));
    assertThat(probes).hasSize(6);
    assertThat(probes).extracting(Value.Probe::round).containsOnly(4L);
    assertThat(probes)
        .filteredOn(p -> p.subject().equals("rmqv.q"))
        .extracting(Value.Probe::leader)
        .containsOnly("rabbit2");
    assertThat(probes)
        .filteredOn(p -> p.subject().equals("cluster"))
        .extracting(Value.Probe::runningNodes)
        .containsOnly(List.of("rabbit1", "rabbit2", "rabbit3"));
    http.statuses.put("/api/nodes", 500);
    assertThat(control.probe(5, List.of("rmqv.q"))).isEmpty();
  }

  @Test
  void brokerVersionFailsWhenNoNodeAnswers() {
    http.statuses.put("/api/overview", 500);
    assertThatThrownBy(control::brokerVersion).hasMessageContaining("no node reports a version");
  }
}
