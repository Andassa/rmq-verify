package io.github.andassa.rmqverify.cluster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** {@link ProcessRunner} backed by {@link ProcessBuilder}, without any shell. */
public final class SystemProcessRunner implements ProcessRunner {

  private static final int MAX_CAPTURE = 256 * 1024;

  @Override
  public ProcessResult run(List<String> command, Duration timeout) throws ClusterException {
    Process process;
    try {
      process = new ProcessBuilder(List.copyOf(command)).start();
    } catch (IOException e) {
      throw new ClusterException("cannot start " + command.get(0) + ": " + e.getMessage(), e);
    }
    CompletableFuture<String> out =
        CompletableFuture.supplyAsync(() -> capture(process.getInputStream()));
    CompletableFuture<String> err =
        CompletableFuture.supplyAsync(() -> capture(process.getErrorStream()));
    try {
      process.getOutputStream().close();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new ClusterException(command + " timed out after " + timeout);
      }
      return new ProcessResult(process.exitValue(), out.get(), err.get());
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new ClusterException(command + " interrupted", e);
    } catch (IOException | ExecutionException e) {
      throw new ClusterException(command + " output could not be read", e);
    }
  }

  private static String capture(InputStream stream) {
    try (InputStream in = stream) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int read;
      while ((read = in.read(chunk)) >= 0) {
        if (buffer.size() < MAX_CAPTURE) {
          buffer.write(chunk, 0, Math.min(read, MAX_CAPTURE - buffer.size()));
        }
      }
      return buffer.toString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
