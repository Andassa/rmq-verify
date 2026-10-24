package io.github.andassa.rmqverify.cluster;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Client of the Toxiproxy HTTP API, used for faults between the client and the brokers. */
public final class ToxiproxyClient {

  private final URI base;
  private final HttpJson http;

  /**
   * Creates the client.
   *
   * @param base Toxiproxy API base URI
   */
  public ToxiproxyClient(URI base) {
    this.base = base;
    this.http = new HttpJson(Duration.ofSeconds(10), Optional.empty());
  }

  /**
   * Enables or disables a proxy. A disabled proxy closes existing connections and refuses new ones.
   *
   * @param proxy proxy name
   * @param enabled target state
   * @throws ClusterException if the API call fails
   */
  public void setEnabled(String proxy, boolean enabled) throws ClusterException {
    NodeSpec.requireSafe(proxy, "proxy");
    http.post(base.resolve("/proxies/" + proxy), Map.of("enabled", enabled));
  }

  /**
   * Adds a toxic to a proxy.
   *
   * @param proxy proxy name
   * @param name toxic name, unique per proxy
   * @param type toxic type, for example {@code latency} or {@code timeout}
   * @param attributes numeric attributes of the toxic
   * @throws ClusterException if the API call fails
   */
  public void addToxic(String proxy, String name, String type, Map<String, Long> attributes)
      throws ClusterException {
    NodeSpec.requireSafe(proxy, "proxy");
    NodeSpec.requireSafe(name, "toxic");
    NodeSpec.requireSafe(type, "type");
    http.post(
        base.resolve("/proxies/" + proxy + "/toxics"),
        Map.of(
            "name",
            name,
            "type",
            type,
            "stream",
            "downstream",
            "toxicity",
            1.0,
            "attributes",
            attributes));
  }

  /**
   * Removes every toxic and re-enables every proxy.
   *
   * @throws ClusterException if the API call fails
   */
  public void reset() throws ClusterException {
    http.post(base.resolve("/reset"), Map.of());
  }
}
