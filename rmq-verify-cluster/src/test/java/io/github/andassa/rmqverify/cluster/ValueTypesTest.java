package io.github.andassa.rmqverify.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValueTypesTest {

  @Test
  void topologyOfTheComposeFile() {
    Topology local = Topology.local();
    assertThat(local.nodeNames()).containsExactly("rabbit1", "rabbit2", "rabbit3");
    assertThat(local.node("rabbit2").orElseThrow().amqpProxy()).isEqualTo("amqp-rabbit2");
    assertThat(local.node("rabbit2").orElseThrow().streamProxy()).isEqualTo("stream-rabbit2");
    assertThat(local.node("nope")).isEmpty();
    NodeSpec n = local.nodes().get(0);
    assertThatThrownBy(
            () -> new Topology("rmqv", "net", "h", "v", URI.create("http://x"), List.of(n, n)))
        .hasMessageContaining("unique");
    assertThatThrownBy(
            () -> new Topology("rmqv", "net", "h", "v", URI.create("http://x"), List.of()))
        .hasMessageContaining("at least one node");
    assertThatThrownBy(() -> new Topology("rmqv", "net", "h", "v", null, List.of(n)))
        .hasMessageContaining("toxiproxy");
    assertThatThrownBy(
            () -> new Topology("bad label", "net", "h", "v", URI.create("http://x"), List.of(n)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nodeSpecValidation() {
    URI ok = URI.create("http://127.0.0.1:1");
    assertThat(new NodeSpec("a", "b", URI.create("https://h"), 1, 65_535).streamPort())
        .isEqualTo(65_535);
    assertThatThrownBy(() -> new NodeSpec("a", "b", URI.create("file:///etc"), 1, 1))
        .hasMessageContaining("http(s)");
    assertThatThrownBy(() -> new NodeSpec("a", "b", null, 1, 1)).hasMessageContaining("http(s)");
    assertThatThrownBy(() -> new NodeSpec("a", "b", ok, 0, 1)).hasMessageContaining("amqpPort");
    assertThatThrownBy(() -> new NodeSpec("a", "b", ok, 1, 65_536))
        .hasMessageContaining("streamPort");
    assertThatThrownBy(() -> new NodeSpec("a b", "b", ok, 1, 1)).hasMessageContaining("name");
  }

  @Test
  void credentialsNeverPrintThePassword() throws Exception {
    Credentials c = Credentials.from(Map.of("X_USER", "u", "X_PASSWORD", "hunter2"), "X");
    assertThat(c.toString()).doesNotContain("hunter2").contains("u");
    assertThatThrownBy(() -> Credentials.from(Map.of("X_USER", "u"), "X"))
        .hasMessageContaining("make up");
    assertThatThrownBy(() -> Credentials.from(Map.of("X_USER", " ", "X_PASSWORD", "p"), "X"))
        .hasMessageContaining("make up");
    assertThatThrownBy(() -> Credentials.from(Map.of("X_USER", "u", "X_PASSWORD", ""), "X"))
        .hasMessageContaining("make up");
    assertThatThrownBy(() -> new Credentials("u", "")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Credentials(null, "p"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void envFilesAreParsedLiterally(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(".env");
    Files.writeString(file, "# comment\n\nA_USER=x=y\nB=$(whoami)\n");
    assertThat(EnvFile.read(file)).containsEntry("A_USER", "x=y").containsEntry("B", "$(whoami)");
    assertThat(EnvFile.read(dir.resolve("absent"))).isEmpty();
    assertThat(EnvFile.withProcessEnv(file, Map.of("B", "override")))
        .containsEntry("B", "override");
    Files.writeString(file, "export A=1\n");
    assertThatThrownBy(() -> EnvFile.read(file)).hasMessageContaining("line 1 is not KEY=VALUE");
    Files.writeString(file, "=1\n");
    assertThatThrownBy(() -> EnvFile.read(file)).hasMessageContaining("not KEY=VALUE");
    Files.writeString(file, "A=" + "x".repeat(70_000));
    assertThatThrownBy(() -> EnvFile.read(file)).hasMessageContaining("larger than");
    assertThatThrownBy(() -> EnvFile.read(dir)).hasMessageContaining("cannot read");
  }

  @Test
  void logsAreSingleLineAndBounded() {
    assertThat(Logs.safe("a\r\nb")).isEqualTo("a  b");
    assertThat(Logs.safe(null)).isEqualTo("null");
    assertThat(Logs.safe("x".repeat(600))).hasSize(515).endsWith("...");
    assertThat(Logs.safe("x".repeat(512))).hasSize(512);
  }

  @Test
  void systemProcessRunnerCapturesOutputAndEnforcesTimeouts() throws Exception {
    SystemProcessRunner runner = new SystemProcessRunner();
    ProcessResult ok =
        runner.run(List.of("sh", "-c", "echo out; echo err >&2; exit 3"), Duration.ofSeconds(10));
    assertThat(ok.exitCode()).isEqualTo(3);
    assertThat(ok.succeeded()).isFalse();
    assertThat(ok.stdout()).isEqualTo("out\n");
    assertThat(ok.stderr()).isEqualTo("err\n");
    assertThat(runner.run(List.of("true"), Duration.ofSeconds(10)).succeeded()).isTrue();
    assertThatThrownBy(() -> runner.run(List.of("sleep", "5"), Duration.ofMillis(200)))
        .hasMessageContaining("timed out");
    assertThatThrownBy(() -> runner.run(List.of("/nonexistent/binary"), Duration.ofSeconds(1)))
        .hasMessageContaining("cannot start");
  }

  @Test
  void viewsCopyTheirLists() {
    java.util.List<String> partitions = new java.util.ArrayList<>(List.of("a"));
    NodeView view = new NodeView("n", true, partitions);
    partitions.add("b");
    assertThat(view.partitions()).containsExactly("a");
    assertThat(new QueueView("q", "quorum", "n", List.of("n"), 0).members()).containsExactly("n");
    assertThat(new ContainerInfo("c", true, false, Map.of(), Map.of()).labels()).isEmpty();
  }
}
