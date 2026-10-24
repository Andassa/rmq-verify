package io.github.andassa.rmqverify.cluster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the {@code docker} command line. Every argument is passed separately and every
 * container name is validated against {@link NodeSpec#SAFE_NAME}, so no input can be interpreted as
 * an option or a shell construct.
 */
public final class DockerCli {

  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final ProcessRunner runner;
  private final ObjectMapper json = new ObjectMapper();

  /**
   * Creates the wrapper.
   *
   * @param runner executor of the {@code docker} commands
   */
  public DockerCli(ProcessRunner runner) {
    this.runner = runner;
  }

  /**
   * Inspects a container.
   *
   * @param container container name
   * @return its state, labels and addresses
   * @throws ClusterException if the container does not exist or the output is unreadable
   */
  public ContainerInfo inspect(String container) throws ClusterException {
    NodeSpec.requireSafe(container, "container");
    JsonNode root =
        parse(check(List.of("docker", "inspect", "--type", "container", container)).stdout());
    JsonNode c = root.path(0);
    Map<String, String> labels = new HashMap<>();
    c.path("Config")
        .path("Labels")
        .properties()
        .forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
    Map<String, String> ips = new HashMap<>();
    c.path("NetworkSettings")
        .path("Networks")
        .properties()
        .forEach(e -> ips.put(e.getKey(), e.getValue().path("IPAddress").asText()));
    JsonNode state = c.path("State");
    return new ContainerInfo(
        c.path("Name").asText().replaceFirst("^/", ""),
        state.path("Running").asBoolean(),
        state.path("Paused").asBoolean(),
        labels,
        ips);
  }

  /**
   * Reads the labels of a network.
   *
   * @param network network name
   * @return its labels
   * @throws ClusterException if the network does not exist
   */
  public Map<String, String> networkLabels(String network) throws ClusterException {
    NodeSpec.requireSafe(network, "network");
    JsonNode root = parse(check(List.of("docker", "network", "inspect", network)).stdout());
    Map<String, String> labels = new HashMap<>();
    root.path(0)
        .path("Labels")
        .properties()
        .forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
    return labels;
  }

  /**
   * Sends {@code SIGKILL} to the main process of a container.
   *
   * @param container container name
   * @throws ClusterException if docker fails
   */
  public void kill(String container) throws ClusterException {
    lifecycle("kill", "--signal", "KILL", container);
  }

  /**
   * Starts a stopped container.
   *
   * @param container container name
   * @throws ClusterException if docker fails
   */
  public void start(String container) throws ClusterException {
    lifecycle("start", container);
  }

  /**
   * Stops a container gracefully.
   *
   * @param container container name
   * @throws ClusterException if docker fails
   */
  public void stop(String container) throws ClusterException {
    lifecycle("stop", "--time", "30", container);
  }

  /**
   * Freezes every process of a container ({@code SIGSTOP} through the freezer cgroup).
   *
   * @param container container name
   * @throws ClusterException if docker fails
   */
  public void pause(String container) throws ClusterException {
    lifecycle("pause", container);
  }

  /**
   * Resumes a frozen container.
   *
   * @param container container name
   * @throws ClusterException if docker fails
   */
  public void unpause(String container) throws ClusterException {
    lifecycle("unpause", container);
  }

  /**
   * Runs a command inside a container.
   *
   * @param container container name
   * @param asRoot whether to run as uid 0 (required for iptables and tc)
   * @param command program and arguments
   * @return the result, whatever the exit status
   * @throws ClusterException if docker cannot run the command
   */
  public ProcessResult exec(String container, boolean asRoot, List<String> command)
      throws ClusterException {
    NodeSpec.requireSafe(container, "container");
    List<String> full = new ArrayList<>(List.of("docker", "exec"));
    if (asRoot) {
      full.addAll(List.of("--user", "0"));
    }
    full.add(container);
    full.addAll(command);
    return runner.run(full, TIMEOUT);
  }

  private void lifecycle(String verb, String... arguments) throws ClusterException {
    NodeSpec.requireSafe(arguments[arguments.length - 1], "container");
    List<String> command = new ArrayList<>(List.of("docker", verb));
    command.addAll(List.of(arguments));
    check(command);
  }

  private ProcessResult check(List<String> command) throws ClusterException {
    ProcessResult result = runner.run(command, TIMEOUT);
    if (!result.succeeded()) {
      throw new ClusterException(
          String.join(" ", command)
              + " failed ("
              + result.exitCode()
              + "): "
              + result.stderr().strip());
    }
    return result;
  }

  private JsonNode parse(String output) throws ClusterException {
    try {
      return json.readTree(output);
    } catch (JsonProcessingException e) {
      throw new ClusterException("unreadable docker output", e);
    }
  }
}
