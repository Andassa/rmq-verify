package io.github.andassa.rmqverify.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Minimal JSON-over-HTTP client with timeouts, optional basic authentication and bounded bodies.
 */
final class HttpJson {

  private static final int MAX_BODY = 4 * 1024 * 1024;

  private final HttpClient client;
  private final Duration timeout;
  private final Optional<String> authorization;
  private final ObjectMapper json = new ObjectMapper();

  HttpJson(Duration timeout, Optional<Credentials> credentials) {
    this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    this.timeout = timeout;
    this.authorization =
        credentials.map(
            c ->
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString(
                            (c.user() + ":" + c.password()).getBytes(StandardCharsets.UTF_8)));
  }

  JsonNode get(URI uri) throws ClusterException {
    return send(request(uri).GET().build());
  }

  JsonNode post(URI uri, Object body) throws ClusterException {
    return send(request(uri).header("Content-Type", "application/json").POST(body(body)).build());
  }

  /** Returns empty on 404, for resources that may legitimately be absent. */
  Optional<JsonNode> find(URI uri) throws ClusterException {
    try {
      HttpResponse<byte[]> response =
          client.send(request(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() == 404) {
        return Optional.empty();
      }
      return Optional.of(read(response, "GET", uri));
    } catch (IOException e) {
      throw new ClusterException("GET " + uri + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ClusterException("GET " + uri + " interrupted", e);
    }
  }

  private HttpRequest.Builder request(URI uri) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
    authorization.ifPresent(a -> builder.header("Authorization", a));
    return builder;
  }

  private HttpRequest.BodyPublisher body(Object body) throws ClusterException {
    try {
      return HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
    } catch (IOException e) {
      throw new ClusterException("cannot encode request body", e);
    }
  }

  private JsonNode read(HttpResponse<byte[]> response, String method, URI uri)
      throws ClusterException, IOException {
    byte[] body = response.body();
    if (response.statusCode() / 100 != 2) {
      throw new ClusterException(method + " " + uri + " returned " + response.statusCode());
    }
    if (body.length > MAX_BODY) {
      throw new ClusterException(uri + " returned more than " + MAX_BODY + " bytes");
    }
    return body.length == 0 ? json.missingNode() : json.readTree(body);
  }

  private JsonNode send(HttpRequest request) throws ClusterException {
    try {
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      return read(response, request.method(), request.uri());
    } catch (IOException e) {
      throw new ClusterException(
          request.method() + " " + request.uri() + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ClusterException(request.method() + " " + request.uri() + " interrupted", e);
    }
  }
}
