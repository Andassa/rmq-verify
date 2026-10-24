package io.github.andassa.rmqverify.cluster;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * One RabbitMQ node of the test cluster and how the client reaches it.
 *
 * @param name logical node name, also the broker host name ({@code rabbit1})
 * @param container Docker container name ({@code rmqv-rabbit1})
 * @param management base URI of the node's management API, reached directly
 * @param amqpPort local port of the Toxiproxy proxy in front of the node's AMQP listener
 * @param streamPort local port of the Toxiproxy proxy in front of the node's stream listener
 */
public record NodeSpec(
    String name, String container, URI management, int amqpPort, int streamPort) {

  /** Container and node names accepted anywhere a command is built. */
  public static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}");

  /** Validates names, which end up as arguments of external commands. */
  public NodeSpec {
    requireSafe(name, "name");
    requireSafe(container, "container");
    boolean web =
        management != null
            && ("http".equals(management.getScheme()) || "https".equals(management.getScheme()));
    if (!web) {
      throw new IllegalArgumentException("management must be an http(s) URI");
    }
    requirePort(amqpPort, "amqpPort");
    requirePort(streamPort, "streamPort");
  }

  /**
   * Name of the Toxiproxy proxy in front of the AMQP listener.
   *
   * @return {@code amqp-<name>}
   */
  public String amqpProxy() {
    return "amqp-" + name;
  }

  /**
   * Name of the Toxiproxy proxy in front of the stream listener.
   *
   * @return {@code stream-<name>}
   */
  public String streamProxy() {
    return "stream-" + name;
  }

  static void requireSafe(String value, String field) {
    if (value == null || !SAFE_NAME.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " is not a safe name: " + value);
    }
  }

  private static void requirePort(int port, String field) {
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException(field + " out of range: " + port);
    }
  }
}
