package io.github.andassa.rmqverify.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.Credentials;
import io.github.andassa.rmqverify.cluster.DockerCli;
import io.github.andassa.rmqverify.cluster.DockerClusterControl;
import io.github.andassa.rmqverify.cluster.ProcessResult;
import io.github.andassa.rmqverify.cluster.SystemProcessRunner;
import io.github.andassa.rmqverify.cluster.Topology;
import io.github.andassa.rmqverify.cluster.ToxiproxyClient;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.VerdictStatus;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.FaultPlan;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.ComposeContainer;

/**
 * Real cluster, real faults. The cluster of {@code deploy/docker-compose.yml} is started by
 * Testcontainers with fresh random credentials; the checkers must detect a loss we cause on purpose
 * and must stay silent on a healthy run and across a real {@code kill -9} of a quorum queue member.
 */
class ClusterIT {

  private static final List<String> NODES = List.of("rabbit1", "rabbit2", "rabbit3");
  private static ComposeContainer compose;
  private static Path envFile;

  @BeforeAll
  static void startCluster(@TempDir Path dir) throws Exception {
    Path root =
        Path.of(System.getenv().getOrDefault("RMQV_ROOT", "..")).toAbsolutePath().normalize();
    DockerCli docker = new DockerCli(new SystemProcessRunner());
    ProcessResult running =
        new SystemProcessRunner()
            .run(
                List.of("docker", "ps", "-q", "--filter", "name=rmqv-rabbit1"),
                Duration.ofSeconds(30));
    assertThat(running.stdout().isBlank())
        .as("another rmq-verify cluster is running: make down first")
        .isTrue();
    Map<String, String> images =
        io.github.andassa.rmqverify.cluster.EnvFile.read(root.resolve("deploy/images.env"));
    String base =
        images.get("RABBITMQ_" + System.getenv().getOrDefault("RABBITMQ", "4.4").replace('.', '_'));
    Map<String, String> env = new TreeMap<>();
    env.put("RMQV_RABBITMQ_BASE_IMAGE", base);
    env.put("RMQV_RABBITMQ_TAG", base.substring("rabbitmq:".length(), base.indexOf('@')));
    env.put("RMQV_TOXIPROXY_IMAGE", images.get("TOXIPROXY"));
    env.put("RMQV_ADMIN_USER", "rmqv_admin");
    env.put("RMQV_ADMIN_PASSWORD", secret());
    env.put("RMQV_APP_USER", "rmqv_app");
    env.put("RMQV_APP_PASSWORD", secret());
    env.put("RMQV_ERLANG_COOKIE", secret().toUpperCase(java.util.Locale.ROOT));
    compose =
        new ComposeContainer(new File(root.resolve("deploy/docker-compose.yml").toString()))
            .withEnv(env)
            .withBuild(true)
            .withRemoveVolumes(true)
            .withStartupTimeout(Duration.ofMinutes(10));
    compose.start();
    ClusterControl cluster =
        new DockerClusterControl(
            Topology.local(),
            docker,
            new ToxiproxyClient(Topology.local().toxiproxy()),
            new Credentials("rmqv_admin", env.get("RMQV_ADMIN_PASSWORD")));
    cluster.waitHealthy(Duration.ofMinutes(5));
    rabbitmqctl(docker, "import_definitions", "/etc/rabbitmq/definitions.json");
    rabbitmqctl(docker, "add_user", "rmqv_app", env.get("RMQV_APP_PASSWORD"));
    rabbitmqctl(
        docker,
        "set_permissions",
        "-p",
        "rmqv",
        "rmqv_app",
        "^rmqv\\..*",
        "^(rmqv\\..*|amq\\.default)$",
        "^rmqv\\..*");
    rabbitmqctl(docker, "set_permissions", "-p", "rmqv", "rmqv_admin", ".*", ".*", ".*");
    envFile = Files.createTempFile("rmqv-e2e", ".env");
    StringBuilder content = new StringBuilder();
    env.forEach((k, v) -> content.append(k).append('=').append(v).append('\n'));
    Files.writeString(envFile, content, StandardCharsets.UTF_8);
  }

  @AfterAll
  static void stopCluster() throws Exception {
    if (compose != null) {
      compose.stop();
    }
    if (envFile != null) {
      Files.deleteIfExists(envFile);
    }
  }

  private static void rabbitmqctl(DockerCli docker, String... args) throws Exception {
    List<String> command = new java.util.ArrayList<>(List.of("rabbitmqctl"));
    command.addAll(List.of(args));
    ProcessResult result = docker.exec("rmqv-rabbit1", false, command);
    assertThat(result.succeeded()).as(String.join(" ", command) + ": " + result.stderr()).isTrue();
  }

  private static String secret() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static Experiment.RunOutcome run(
      Path dir, String scenario, FaultPlan plan, String... extra) throws Exception {
    RunOptions options = new RunOptions();
    picocli.CommandLine cli = new picocli.CommandLine(options);
    List<String> args =
        new java.util.ArrayList<>(
            List.of(
                "--duration",
                "25",
                "--publishers",
                "2",
                "--rate",
                "20",
                "--settle",
                "5",
                "--scenario",
                scenario,
                "--out",
                dir.resolve(scenario).toString(),
                "--env-file",
                envFile.toString()));
    args.addAll(List.of(extra));
    cli.parseArgs(args.toArray(String[]::new));
    StringWriter out = new StringWriter();
    Experiment.RunOutcome outcome =
        CliContext.experiment(options, new PrintWriter(out))
            .run(options.settings(plan.seed()), plan);
    RunArtifacts.write(outcome.directory(), outcome.summary(), outcome.history());
    return outcome;
  }

  private static Verdict verdict(Experiment.RunOutcome outcome, String checker) {
    return outcome.summary().verdicts().stream()
        .filter(v -> v.checker().equals(checker))
        .findFirst()
        .orElseThrow();
  }

  private static FaultPlan noFault() {
    return new FaultPlan(1, 11, 25_000, NODES, List.of());
  }

  @Test
  void aHealthyRunIsValidForEveryChecker(@TempDir Path dir) throws Exception {
    Experiment.RunOutcome outcome = run(dir, "e2e-healthy", noFault(), "--queue-type", "QUORUM");
    assertThat(outcome.summary().verdicts())
        .allSatisfy(
            v ->
                assertThat(v.status())
                    .as(v.checker() + ": " + v.summary())
                    .isEqualTo(VerdictStatus.VALID));
    assertThat(outcome.history().size()).isGreaterThan(100);
  }

  @Test
  void aKnownLossIsDetected(@TempDir Path dir) throws Exception {
    Experiment.RunOutcome outcome =
        run(
            dir,
            "e2e-known-loss",
            noFault(),
            "--queue-type",
            "CLASSIC",
            "--max-length",
            "20",
            "--consumers",
            "0");
    Verdict lost = verdict(outcome, "LostMessages");
    assertThat(lost.status()).isEqualTo(VerdictStatus.INVALID);
    assertThat(lost.metrics().get("lostConfirmed")).isEqualTo(lost.metrics().get("confirmed") - 20);
    assertThat(verdict(outcome, "FifoOrdering").status()).isEqualTo(VerdictStatus.INVALID);
  }

  @Test
  void aKilledQuorumMemberLosesNothingConfirmed(@TempDir Path dir) throws Exception {
    FaultPlan kill =
        new FaultPlan(
            1,
            12,
            25_000,
            NODES,
            List.of(
                new FaultEvent(0, "kill-node", 5_000, 8_000, List.of("rabbit2"), new TreeMap<>())));
    Experiment.RunOutcome outcome =
        run(dir, "e2e-kill", kill, "--queue-type", "QUORUM", "--consumers", "0");
    assertThat(outcome.history().operations())
        .filteredOn(o -> o.function() == OpFunction.NEMESIS_START)
        .singleElement()
        .satisfies(o -> assertThat(o.error()).isEmpty());
    assertThat(verdict(outcome, "LostMessages").status()).isEqualTo(VerdictStatus.VALID);
    assertThat(verdict(outcome, "ConfirmedButMissingAfterRecovery").status())
        .isEqualTo(VerdictStatus.VALID);
    assertThat(
            verdict(outcome, "ConfirmedButMissingAfterRecovery").metrics().get("exposedMessages"))
        .isPositive();
  }
}
