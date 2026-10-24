package io.github.andassa.rmqverify.cluster;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-process HTTP server answering canned JSON per path, recording every request. */
final class FakeHttp implements AutoCloseable {

  record Request(String method, String path, String body, String authorization) {}

  final List<Request> requests = new ArrayList<>();
  final Map<String, String> responses = new ConcurrentHashMap<>();
  final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final HttpServer server;

  FakeHttp() throws IOException {
    server =
        HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String path = exchange.getRequestURI().getRawPath();
          synchronized (requests) {
            requests.add(
                new Request(
                    exchange.getRequestMethod(),
                    path,
                    body,
                    exchange.getRequestHeaders().getFirst("Authorization")));
          }
          byte[] answer = responses.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(
              statuses.getOrDefault(path, 200), answer.length == 0 ? -1 : answer.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(answer);
          }
        });
    server.start();
  }

  URI uri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  List<Request> requests() {
    synchronized (requests) {
      return List.copyOf(requests);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
