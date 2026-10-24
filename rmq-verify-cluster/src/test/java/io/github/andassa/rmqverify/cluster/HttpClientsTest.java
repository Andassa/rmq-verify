package io.github.andassa.rmqverify.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpClientsTest {

  private FakeHttp http;

  @BeforeEach
  void start() throws Exception {
    http = new FakeHttp();
  }

  @AfterEach
  void stop() {
    http.close();
  }

  @Test
  void managementClientReadsNodesQueuesAndVersion() throws Exception {
    http.responses.put("/api/overview", "{\"rabbitmq_version\":\"4.4.0\"}");
    http.responses.put(
        "/api/nodes",
        "[{\"name\":\"rabbit@rabbit1\",\"running\":true,\"partitions\":[]},"
            + "{\"name\":\"rabbit@rabbit2\",\"running\":false,\"partitions\":[\"rabbit@rabbit3\"]}]");
    http.responses.put(
        "/api/queues/rmqv/rmqv.q%201",
        "{\"name\":\"rmqv.q 1\",\"type\":\"quorum\",\"leader\":\"rabbit@rabbit2\",\"members\":[\"rabbit@rabbit1\","
            + "\"rabbit@rabbit2\"],\"messages\":7}");
    http.responses.put(
        "/api/queues/rmqv/classic",
        "{\"name\":\"classic\",\"type\":\"classic\",\"node\":\"rabbit@rabbit3\"}");
    http.statuses.put("/api/queues/rmqv/missing", 404);
    ManagementClient client =
        new ManagementClient(http.uri(), new Credentials("admin", "s3cret"), Duration.ofSeconds(2));

    assertThat(client.version()).isEqualTo("4.4.0");
    assertThat(client.nodes())
        .containsExactly(
            new NodeView("rabbit1", true, java.util.List.of()),
            new NodeView("rabbit2", false, java.util.List.of("rabbit3")));
    QueueView quorum = client.queue("rmqv", "rmqv.q 1").orElseThrow();
    assertThat(quorum.leader()).isEqualTo("rabbit2");
    assertThat(quorum.members()).containsExactly("rabbit1", "rabbit2");
    assertThat(quorum.messages()).isEqualTo(7);
    assertThat(client.queue("rmqv", "classic").orElseThrow().leader()).isEqualTo("rabbit3");
    assertThat(client.queue("rmqv", "missing")).isEmpty();
    assertThat(http.requests().get(0).authorization()).isEqualTo("Basic YWRtaW46czNjcmV0");
    assertThat(ManagementClient.shortName("plain")).isEqualTo("plain");
  }

  @Test
  void errorsAreReportedWithTheUri() {
    http.statuses.put("/api/overview", 401);
    ManagementClient client =
        new ManagementClient(http.uri(), new Credentials("a", "b"), Duration.ofSeconds(2));
    assertThatThrownBy(client::version)
        .isInstanceOf(ClusterException.class)
        .hasMessageContaining("returned 401");
    http.statuses.put("/api/queues/rmqv/q", 500);
    assertThatThrownBy(() -> client.queue("rmqv", "q")).hasMessageContaining("returned 500");
    http.close();
    assertThatThrownBy(client::nodes).hasMessageContaining("failed");
    assertThatThrownBy(() -> client.queue("rmqv", "q")).hasMessageContaining("failed");
  }

  @Test
  void toxiproxyClientSendsTheExpectedRequests() throws Exception {
    ToxiproxyClient toxiproxy = new ToxiproxyClient(http.uri());
    toxiproxy.setEnabled("amqp-rabbit1", false);
    toxiproxy.addToxic("amqp-rabbit1", "rmqv-latency", "latency", Map.of("latency", 1000L));
    toxiproxy.reset();
    assertThat(http.requests())
        .extracting(FakeHttp.Request::path)
        .containsExactly("/proxies/amqp-rabbit1", "/proxies/amqp-rabbit1/toxics", "/reset");
    assertThat(http.requests().get(0).body()).isEqualTo("{\"enabled\":false}");
    assertThat(http.requests().get(1).body())
        .contains("\"type\":\"latency\"", "\"latency\":1000", "\"stream\":\"downstream\"");
    assertThatThrownBy(() -> toxiproxy.setEnabled("../reset", true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
