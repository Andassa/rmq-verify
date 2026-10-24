package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.checker.Checkers;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.cluster.Credentials;
import io.github.andassa.rmqverify.cluster.DockerCli;
import io.github.andassa.rmqverify.cluster.DockerClusterControl;
import io.github.andassa.rmqverify.cluster.EnvFile;
import io.github.andassa.rmqverify.cluster.NodeSpec;
import io.github.andassa.rmqverify.cluster.SystemProcessRunner;
import io.github.andassa.rmqverify.cluster.Topology;
import io.github.andassa.rmqverify.cluster.ToxiproxyClient;
import io.github.andassa.rmqverify.model.NanoClock;
import io.github.andassa.rmqverify.nemesis.FaultPlanCodec;
import io.github.andassa.rmqverify.nemesis.NemesisRegistry;
import io.github.andassa.rmqverify.nemesis.NemesisRunner;
import io.github.andassa.rmqverify.nemesis.ShutdownHooks;
import io.github.andassa.rmqverify.nemesis.Sleeper;
import io.github.andassa.rmqverify.workload.BrokerAccess;
import io.github.andassa.rmqverify.workload.Tls;
import io.github.andassa.rmqverify.workload.WorkloadException;
import io.github.andassa.rmqverify.workload.WorkloadRunner;
import io.github.andassa.rmqverify.workload.amqp.RabbitConnector;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Wires the real adapters (Docker, Toxiproxy, AMQP, stream) from {@code .env} and the topology. */
final class CliContext {

  private CliContext() {}

  static Experiment experiment(RunOptions options, PrintWriter out)
      throws ClusterException, WorkloadException {
    Path envFile = options.envFile;
    Optional<Tls> tls =
        options.tlsCa == null ? Optional.empty() : Optional.of(Tls.fromCa(options.tlsCa));
    Map<String, String> env = EnvFile.withProcessEnv(envFile, System.getenv());
    Topology topology = Topology.local();
    Credentials admin = Credentials.from(env, "RMQV_ADMIN");
    Credentials app = Credentials.from(env, "RMQV_APP");
    DockerClusterControl cluster =
        new DockerClusterControl(
            topology,
            new DockerCli(new SystemProcessRunner()),
            new ToxiproxyClient(topology.toxiproxy()),
            admin);
    Map<String, Integer> streamPorts = new LinkedHashMap<>();
    for (NodeSpec node : topology.nodes()) {
      streamPorts.put(node.name(), node.streamPort());
    }
    BrokerAccess access =
        new BrokerAccess(
            topology.host(),
            topology.nodes().stream().map(NodeSpec::amqpPort).toList(),
            streamPorts,
            topology.vhost(),
            app.user(),
            app.password(),
            tls);
    NemesisRegistry registry = NemesisRegistry.load();
    NanoClock clock = NanoClock.system();
    WorkloadFactory workloads =
        (config, recorder) ->
            new WorkloadRunner(
                config, access, new RabbitConnector(access, config.seed()), recorder, clock);
    return new Experiment(
        cluster,
        workloads,
        recorder ->
            new NemesisRunner(
                registry, cluster, recorder, clock, Sleeper.real(), ShutdownHooks.runtime()),
        new FaultPlanCodec(registry),
        Checkers.load(),
        clock,
        Sleeper.real(),
        out);
  }
}
