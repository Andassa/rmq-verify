package io.github.andassa.rmqverify.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Read-only client of one node's management API: what this node believes about the cluster. */
public final class ManagementClient {

  private final URI base;
  private final HttpJson http;

  /**
   * Creates a client for one node.
   *
   * @param base management base URI of the node
   * @param credentials monitoring credentials
   * @param timeout per-request timeout
   */
  public ManagementClient(URI base, Credentials credentials, Duration timeout) {
    this.base = base;
    this.http = new HttpJson(timeout, Optional.of(credentials));
  }

  /**
   * Returns the broker version reported by the node.
   *
   * @return for example {@code 4.4.0}
   * @throws ClusterException if the node does not answer
   */
  public String version() throws ClusterException {
    return http.get(base.resolve("/api/overview")).path("rabbitmq_version").asText();
  }

  /**
   * Returns the node's view of the cluster membership.
   *
   * @return one entry per known node
   * @throws ClusterException if the node does not answer
   */
  public List<NodeView> nodes() throws ClusterException {
    List<NodeView> views = new ArrayList<>();
    for (JsonNode node : http.get(base.resolve("/api/nodes"))) {
      List<String> partitions = new ArrayList<>();
      node.path("partitions").forEach(p -> partitions.add(shortName(p.asText())));
      views.add(
          new NodeView(
              shortName(node.path("name").asText()), node.path("running").asBoolean(), partitions));
    }
    return views;
  }

  /**
   * Returns the node's view of a queue.
   *
   * @param vhost virtual host
   * @param queue queue name
   * @return the queue, empty if the node does not know it
   * @throws ClusterException if the node does not answer
   */
  public Optional<QueueView> queue(String vhost, String queue) throws ClusterException {
    URI uri = base.resolve("/api/queues/" + encode(vhost) + "/" + encode(queue));
    return http.find(uri)
        .map(
            q -> {
              String leader =
                  q.hasNonNull("leader") ? q.path("leader").asText() : q.path("node").asText();
              List<String> members = new ArrayList<>();
              q.path("members").forEach(m -> members.add(shortName(m.asText())));
              return new QueueView(
                  q.path("name").asText(),
                  q.path("type").asText(),
                  shortName(leader),
                  members,
                  q.path("messages").asLong());
            });
  }

  static String shortName(String erlangNode) {
    int at = erlangNode.indexOf('@');
    return at >= 0 ? erlangNode.substring(at + 1) : erlangNode;
  }

  private static String encode(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
