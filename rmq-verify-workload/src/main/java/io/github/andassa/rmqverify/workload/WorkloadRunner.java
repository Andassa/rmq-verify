package io.github.andassa.rmqverify.workload;

import com.rabbitmq.stream.Environment;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.NanoClock;
import io.github.andassa.rmqverify.workload.amqp.AmqpConnector;
import io.github.andassa.rmqverify.workload.amqp.AmqpConsumer;
import io.github.andassa.rmqverify.workload.amqp.AmqpDrainer;
import io.github.andassa.rmqverify.workload.amqp.AmqpPublisher;
import io.github.andassa.rmqverify.workload.amqp.QueueDeclarer;
import io.github.andassa.rmqverify.workload.stream.StreamConsumer;
import io.github.andassa.rmqverify.workload.stream.StreamDrainer;
import io.github.andassa.rmqverify.workload.stream.StreamEnvironments;
import io.github.andassa.rmqverify.workload.stream.StreamPublisher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Runs a workload in explicit phases so that the caller can interleave faults: prepare, start
 * consumers and publishers, stop publishers, stop consumers, drain. Each client runs on its own
 * virtual thread.
 */
public final class WorkloadRunner implements Workload {

  private final WorkloadConfig config;
  private final HistoryRecorder recorder;
  private final NanoClock clock;
  private final Pacer pacer;
  private final AmqpConnector connector;
  private final Function<BrokerAccess, Environment> environments;
  private final BrokerAccess access;
  private final List<Worker> publishers = new ArrayList<>();
  private final List<Worker> consumers = new ArrayList<>();
  private final List<Thread> publisherThreads = new ArrayList<>();
  private final List<Thread> consumerThreads = new ArrayList<>();
  private Environment environment;

  /**
   * Creates a runner backed by the official clients.
   *
   * @param config the workload
   * @param access broker endpoints
   * @param connector AMQP connection factory
   * @param recorder history recorder
   * @param clock time source
   */
  public WorkloadRunner(
      WorkloadConfig config,
      BrokerAccess access,
      AmqpConnector connector,
      HistoryRecorder recorder,
      NanoClock clock) {
    this(config, access, connector, recorder, clock, Pacer.sleeping(), StreamEnvironments::create);
  }

  WorkloadRunner(
      WorkloadConfig config,
      BrokerAccess access,
      AmqpConnector connector,
      HistoryRecorder recorder,
      NanoClock clock,
      Pacer pacer,
      Function<BrokerAccess, Environment> environments) {
    this.config = config;
    this.access = access;
    this.connector = connector;
    this.recorder = recorder;
    this.clock = clock;
    this.pacer = pacer;
    this.environments = environments;
  }

  /**
   * Declares the queues and opens the stream environment when needed.
   *
   * @throws WorkloadException if the queues cannot be declared
   */
  @Override
  public void prepare() throws WorkloadException {
    new QueueDeclarer(connector).declare(config.queues());
    if (config.kind().isLog()) {
      environment = environments.apply(access);
    }
  }

  /** Starts every consumer. */
  @Override
  public void startConsumers() {
    for (int q = 0; q < config.queues().size(); q++) {
      String queue = config.queues().get(q).name();
      for (int c = 0; c < config.consumersPerQueue(); c++) {
        String process = "consumer-" + q + "-" + c;
        Worker worker =
            config.kind().isLog()
                ? new StreamConsumer(process, queue, environment, recorder, pacer)
                : new AmqpConsumer(process, queue, config.prefetch(), connector, recorder, pacer);
        consumers.add(worker);
        consumerThreads.add(Thread.ofVirtual().name(process).start(worker));
      }
    }
  }

  /** Starts every publisher with its seeded plan. */
  @Override
  public void startPublishers() {
    List<PublisherPlan> plans = PublisherPlan.forAll(config);
    for (int p = 0; p < plans.size(); p++) {
      String process = "publisher-" + p;
      Worker worker =
          config.kind().isLog()
              ? new StreamPublisher(
                  process,
                  config.runId(),
                  plans.get(p),
                  environment,
                  recorder,
                  config.confirmTimeout(),
                  pacer)
              : new AmqpPublisher(
                  process,
                  config.runId(),
                  plans.get(p),
                  connector,
                  recorder,
                  config.confirmTimeout(),
                  pacer);
      publishers.add(worker);
      publisherThreads.add(Thread.ofVirtual().name(process).start(worker));
    }
  }

  /**
   * Stops the publishers and waits for their in-flight publication to complete.
   *
   * @throws InterruptedException if interrupted while waiting
   */
  @Override
  public void stopPublishers() throws InterruptedException {
    stopAll(publishers, publisherThreads);
  }

  /**
   * Stops the consumers.
   *
   * @throws InterruptedException if interrupted while waiting
   */
  @Override
  public void stopConsumers() throws InterruptedException {
    stopAll(consumers, consumerThreads);
  }

  private void stopAll(List<Worker> workers, List<Thread> threads) throws InterruptedException {
    workers.forEach(Worker::stop);
    Duration grace = config.confirmTimeout().plusSeconds(10);
    for (Thread thread : threads) {
      if (!thread.join(grace)) {
        thread.interrupt();
        thread.join(Duration.ofSeconds(5));
      }
    }
  }

  /**
   * Drains every queue, one after the other.
   *
   * @return whether every drain met its end criterion
   * @throws InterruptedException if interrupted
   */
  @Override
  public boolean drain() throws InterruptedException {
    boolean complete = true;
    for (QueueSpec queue : config.queues()) {
      String process = "drain:" + queue.name();
      boolean drained =
          config.kind().isLog()
              ? new StreamDrainer(
                      environment,
                      recorder,
                      clock,
                      pacer,
                      config.drainTimeout(),
                      config.drainQuiet())
                  .drain(queue.name(), process)
              : new AmqpDrainer(
                      connector, recorder, clock, pacer, config.drainTimeout(), config.drainQuiet())
                  .drain(queue.name(), process);
      complete &= drained;
    }
    return complete;
  }

  /**
   * Returns the names of the queues of this workload.
   *
   * @return queue names
   */
  @Override
  public List<String> queueNames() {
    return config.queues().stream().map(QueueSpec::name).toList();
  }

  @Override
  public void close() {
    publishers.forEach(Worker::stop);
    consumers.forEach(Worker::stop);
    if (environment != null) {
      environment.close();
    }
  }
}
