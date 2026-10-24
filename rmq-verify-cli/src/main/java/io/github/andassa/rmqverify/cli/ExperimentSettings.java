package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.checker.CheckerContext;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.workload.QueueSpec;
import io.github.andassa.rmqverify.workload.WorkloadConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Complete configuration of one run.
 *
 * @param outputDir directory receiving the run artefacts
 * @param scenario scenario name, groups repetitions in the matrix
 * @param repetition repetition number, from 1
 * @param runId run identifier
 * @param seed seed of the workload and of the fault plan
 * @param kind queue type
 * @param queues number of queues
 * @param maxLength queue length bound (known-loss control), 0 for none
 * @param publishers number of publishers
 * @param consumersPerQueue consumers per queue
 * @param rate publications per second and publisher
 * @param duration length of the load phase
 * @param payloadBytes payload size
 * @param confirmTimeout publisher confirm timeout
 * @param drainTimeout maximum drain duration per queue
 * @param settle quiet period after the heal, before the final probes and the drain
 * @param probeInterval delay between two probe rounds
 * @param checks checker thresholds
 */
public record ExperimentSettings(
    Path outputDir,
    String scenario,
    int repetition,
    String runId,
    long seed,
    QueueKind kind,
    int queues,
    long maxLength,
    int publishers,
    int consumersPerQueue,
    double rate,
    Duration duration,
    int payloadBytes,
    Duration confirmTimeout,
    Duration drainTimeout,
    Duration settle,
    Duration probeInterval,
    CheckerContext checks) {

  /**
   * Builds the workload configuration of this run.
   *
   * @return the workload
   */
  public WorkloadConfig workload() {
    List<QueueSpec> specs = new ArrayList<>();
    for (int i = 0; i < queues; i++) {
      specs.add(new QueueSpec(queueName(i), kind, maxLength));
    }
    return new WorkloadConfig(
        runId,
        seed,
        specs,
        publishers,
        consumersPerQueue,
        rate,
        payloadBytes,
        payloadBytes,
        Math.max(1, Math.min(100, (int) Math.ceil(rate))),
        confirmTimeout,
        drainTimeout,
        Duration.ofSeconds(3));
  }

  /**
   * Returns the name of the i-th queue: {@code rmqv.<runId>.<kind>.<i>}.
   *
   * @param index queue index
   * @return the queue name
   */
  public String queueName(int index) {
    return "rmqv." + runId + "." + kind.name().toLowerCase(Locale.ROOT) + "." + index;
  }

  /**
   * Returns the settings worth reporting, as strings.
   *
   * @return sorted settings
   */
  public SortedMap<String, String> describe() {
    SortedMap<String, String> map = new TreeMap<>();
    map.put("queues", Integer.toString(queues));
    map.put("maxLength", Long.toString(maxLength));
    map.put("publishers", Integer.toString(publishers));
    map.put("consumersPerQueue", Integer.toString(consumersPerQueue));
    map.put("ratePerPublisher", Double.toString(rate));
    map.put("durationSeconds", Long.toString(duration.toSeconds()));
    map.put("payloadBytes", Integer.toString(payloadBytes));
    map.put("confirmTimeoutMs", Long.toString(confirmTimeout.toMillis()));
    map.put("settleSeconds", Long.toString(settle.toSeconds()));
    return map;
  }
}
