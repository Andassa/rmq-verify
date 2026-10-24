package io.github.andassa.rmqverify.workload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Deterministic stream of messages for one publisher: the same seed and publisher index always give
 * the same queues, sequences, identifiers, sizes and pauses. What the broker does with them is, of
 * course, not deterministic.
 */
public final class PublisherPlan {

  private final SplittableRandom random;
  private final List<QueueSpec> queues;
  private final int minBytes;
  private final int maxBytes;
  private final double rate;
  private final Map<String, Long> nextSequence = new HashMap<>();

  /**
   * One planned publication.
   *
   * @param queue destination
   * @param sequence position in the stream of this publisher for this queue
   * @param uuid message identifier
   * @param size payload size in bytes
   * @param pauseNanos delay to observe before publishing
   */
  public record Planned(String queue, long sequence, String uuid, int size, long pauseNanos) {}

  private PublisherPlan(SplittableRandom random, WorkloadConfig config) {
    this.random = random;
    this.queues = config.queues();
    this.minBytes = config.payloadMinBytes();
    this.maxBytes = config.payloadMaxBytes();
    this.rate = config.ratePerPublisher();
  }

  /**
   * Creates the plans of every publisher from the workload seed.
   *
   * @param config the workload
   * @return one plan per publisher, in publisher order
   */
  public static List<PublisherPlan> forAll(WorkloadConfig config) {
    SplittableRandom root = new SplittableRandom(config.seed());
    PublisherPlan[] plans = new PublisherPlan[config.publishers()];
    for (int i = 0; i < plans.length; i++) {
      plans[i] = new PublisherPlan(root.split(), config);
    }
    return List.of(plans);
  }

  /**
   * Draws the next publication.
   *
   * @return the planned message
   */
  public Planned next() {
    QueueSpec queue = queues.get(random.nextInt(queues.size()));
    long sequence = nextSequence.merge(queue.name(), 1L, Long::sum) - 1;
    String uuid =
        new UUID(
                (random.nextLong() & ~0xF000L) | 0x4000L,
                (random.nextLong() & ~(3L << 62)) | (1L << 63))
            .toString();
    int size = minBytes == maxBytes ? minBytes : minBytes + random.nextInt(maxBytes - minBytes + 1);
    double exponential = -Math.log(1.0 - random.nextDouble()) / rate;
    long pause = (long) Math.min(exponential * 1e9, 10e9);
    return new Planned(queue.name(), sequence, uuid, size, pause);
  }
}
