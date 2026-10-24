package io.github.andassa.rmqverify.cluster;

import java.util.Map;

/**
 * State of a container as reported by {@code docker inspect}.
 *
 * @param name container name
 * @param running whether the main process runs (paused containers are running)
 * @param paused whether the container is frozen
 * @param labels container labels
 * @param ipByNetwork IPv4 address of the container on each attached network
 */
public record ContainerInfo(
    String name,
    boolean running,
    boolean paused,
    Map<String, String> labels,
    Map<String, String> ipByNetwork) {

  /** Copies the maps. */
  public ContainerInfo {
    labels = Map.copyOf(labels);
    ipByNetwork = Map.copyOf(ipByNetwork);
  }
}
