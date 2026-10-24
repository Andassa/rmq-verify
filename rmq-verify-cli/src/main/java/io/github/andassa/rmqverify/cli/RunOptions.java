package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.checker.CheckerContext;
import io.github.andassa.rmqverify.model.QueueKind;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import picocli.CommandLine.Option;

/** Workload and run options shared by {@code run}, {@code replay}, {@code minimize}. */
final class RunOptions {

  @Option(
      names = "--queue-type",
      defaultValue = "QUORUM",
      description = "CLASSIC, QUORUM or STREAM (default: ${DEFAULT-VALUE}).")
  QueueKind queueType;

  @Option(
      names = "--queues",
      defaultValue = "1",
      description = "Number of queues (default: ${DEFAULT-VALUE}).")
  int queues;

  @Option(
      names = "--max-length",
      defaultValue = "0",
      description =
          "x-max-length with drop-head: a control scenario that loses messages on purpose.")
  long maxLength;

  @Option(
      names = "--publishers",
      defaultValue = "4",
      description = "Publishers (default: ${DEFAULT-VALUE}).")
  int publishers;

  @Option(
      names = "--consumers",
      defaultValue = "2",
      description = "Consumers per queue (default: ${DEFAULT-VALUE}).")
  int consumers;

  @Option(
      names = "--rate",
      defaultValue = "50",
      description = "Messages per second and publisher (default: ${DEFAULT-VALUE}).")
  double rate;

  @Option(
      names = "--duration",
      defaultValue = "60",
      description = "Load phase in seconds (default: ${DEFAULT-VALUE}).")
  long durationSeconds;

  @Option(
      names = "--payload-bytes",
      defaultValue = "512",
      description = "Payload size (default: ${DEFAULT-VALUE}).")
  int payloadBytes;

  @Option(
      names = "--confirm-timeout",
      defaultValue = "5000",
      description = "Confirm timeout in ms (default: ${DEFAULT-VALUE}).")
  long confirmTimeoutMillis;

  @Option(
      names = "--drain-timeout",
      defaultValue = "120",
      description = "Drain timeout per queue in s (default: ${DEFAULT-VALUE}).")
  long drainTimeoutSeconds;

  @Option(
      names = "--settle",
      defaultValue = "20",
      description = "Settle time after the heal in s (default: ${DEFAULT-VALUE}).")
  long settleSeconds;

  @Option(
      names = "--max-recovery",
      defaultValue = "60",
      description = "Accepted recovery time in s (default: ${DEFAULT-VALUE}).")
  long maxRecoverySeconds;

  @Option(
      names = "--scenario",
      defaultValue = "adhoc",
      description = "Scenario name used by the matrix.")
  String scenario;

  @Option(
      names = "--repetition",
      defaultValue = "1",
      description = "Repetition number within the scenario.")
  int repetition;

  @Option(names = "--out", description = "Output directory (default: runs/<runId>).")
  Path out;

  @Option(
      names = "--env-file",
      defaultValue = ".env",
      description = "Credentials file (default: ${DEFAULT-VALUE}).")
  Path envFile;

  ExperimentSettings settings(long seed) {
    if (queues < 1
        || queues > 16
        || durationSeconds < 5
        || durationSeconds > 6 * 3600
        || settleSeconds < 0) {
      throw new IllegalArgumentException("queues must be 1-16, duration 5 s to 6 h, settle >= 0");
    }
    String runId =
        "r-"
            + ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            + "-"
            + String.format(Locale.ROOT, "%04x", seed & 0xffff);
    Path dir = out != null ? out : Path.of("runs", runId);
    CheckerContext checks =
        new CheckerContext(
            Duration.ofSeconds(1), Duration.ofSeconds(maxRecoverySeconds), Duration.ofSeconds(15));
    return new ExperimentSettings(
        dir,
        scenario,
        repetition,
        runId,
        seed,
        queueType,
        queues,
        maxLength,
        publishers,
        consumers,
        rate,
        Duration.ofSeconds(durationSeconds),
        payloadBytes,
        Duration.ofMillis(confirmTimeoutMillis),
        Duration.ofSeconds(drainTimeoutSeconds),
        Duration.ofSeconds(settleSeconds),
        Duration.ofSeconds(2),
        checks);
  }
}
