package io.github.andassa.rmqverify.workload;

import io.github.andassa.rmqverify.model.QueueKind;
import java.time.Duration;
import java.util.List;

/**
 * Parameters of a workload. All queues of a run share one kind, so that a run measures one queue
 * type and one client protocol.
 *
 * @param runId run identifier, carried by every payload
 * @param seed seed of the load plan
 * @param queues queues to declare and use
 * @param publishers number of publishers
 * @param consumersPerQueue number of consumers attached to each queue
 * @param ratePerPublisher mean publications per second and publisher (Poisson arrivals)
 * @param payloadMinBytes smallest payload
 * @param payloadMaxBytes largest payload
 * @param prefetch AMQP consumer prefetch
 * @param confirmTimeout how long a publisher waits for a confirm before recording {@code INFO}
 * @param drainTimeout maximum duration of the drain of one queue
 * @param drainQuiet how long a queue must stay empty before its drain is declared complete
 */
public record WorkloadConfig(
    String runId,
    long seed,
    List<QueueSpec> queues,
    int publishers,
    int consumersPerQueue,
    double ratePerPublisher,
    int payloadMinBytes,
    int payloadMaxBytes,
    int prefetch,
    Duration confirmTimeout,
    Duration drainTimeout,
    Duration drainQuiet) {

  /** Validates bounds; every limit protects the test machine, not the broker. */
  public WorkloadConfig {
    if (runId == null || !runId.matches("[a-zA-Z0-9-]{1,64}")) {
      throw new IllegalArgumentException("runId must be 1-64 alphanumerics or dashes");
    }
    if (queues == null || queues.isEmpty() || queues.size() > 16) {
      throw new IllegalArgumentException("between 1 and 16 queues are required");
    }
    queues = List.copyOf(queues);
    if (queues.stream().map(QueueSpec::kind).distinct().count() != 1) {
      throw new IllegalArgumentException("all queues of a run must have the same kind");
    }
    range("publishers", publishers, 1, 64);
    range("consumersPerQueue", consumersPerQueue, 0, 16);
    if (!(ratePerPublisher >= 0.1 && ratePerPublisher <= 5000)) {
      throw new IllegalArgumentException("ratePerPublisher must be within [0.1, 5000]");
    }
    range("payloadMinBytes", payloadMinBytes, 160, 1 << 20);
    range("payloadMaxBytes", payloadMaxBytes, payloadMinBytes, 1 << 20);
    range("prefetch", prefetch, 1, 10_000);
    positive("confirmTimeout", confirmTimeout);
    positive("drainTimeout", drainTimeout);
    positive("drainQuiet", drainQuiet);
  }

  /**
   * Returns the kind shared by every queue.
   *
   * @return the queue kind
   */
  public QueueKind kind() {
    return queues.get(0).kind();
  }

  private static void range(String field, int value, int min, int max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          field + " must be within [" + min + ", " + max + "]: " + value);
    }
  }

  private static void positive(String field, Duration value) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }
}
