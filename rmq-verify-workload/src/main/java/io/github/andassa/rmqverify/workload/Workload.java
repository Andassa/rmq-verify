package io.github.andassa.rmqverify.workload;

import java.util.List;

/** Phases of a workload, in the order a run calls them. */
public interface Workload extends AutoCloseable {

  /**
   * Declares the queues and opens shared client resources.
   *
   * @throws WorkloadException if the queues cannot be declared
   */
  void prepare() throws WorkloadException;

  /** Starts every consumer. */
  void startConsumers();

  /** Starts every publisher. */
  void startPublishers();

  /**
   * Stops the publishers and waits for their in-flight publication.
   *
   * @throws InterruptedException if interrupted
   */
  void stopPublishers() throws InterruptedException;

  /**
   * Stops the consumers.
   *
   * @throws InterruptedException if interrupted
   */
  void stopConsumers() throws InterruptedException;

  /**
   * Drains every queue.
   *
   * @return whether every drain met its end criterion
   * @throws InterruptedException if interrupted
   */
  boolean drain() throws InterruptedException;

  /**
   * Returns the queue names.
   *
   * @return names
   */
  List<String> queueNames();

  @Override
  void close();
}
