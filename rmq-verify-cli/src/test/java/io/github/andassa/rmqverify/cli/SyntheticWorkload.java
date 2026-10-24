package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.workload.Workload;
import io.github.andassa.rmqverify.workload.WorkloadConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Workload that writes a small, correct history synchronously: each phase records what a healthy
 * broker would have produced. {@code loseOne} drops the delivery of the first message.
 */
final class SyntheticWorkload implements Workload {

  private final WorkloadConfig config;
  private final HistoryRecorder recorder;
  private final boolean loseOne;
  private final List<Message> published = new ArrayList<>();
  final List<String> phases = new ArrayList<>();

  SyntheticWorkload(WorkloadConfig config, HistoryRecorder recorder, boolean loseOne) {
    this.config = config;
    this.recorder = recorder;
    this.loseOne = loseOne;
  }

  @Override
  public void prepare() {
    phases.add("prepare");
  }

  @Override
  public void startConsumers() {
    phases.add("startConsumers");
  }

  @Override
  public void startPublishers() {
    phases.add("startPublishers");
    String queue = config.queues().get(0).name();
    for (int i = 0; i < 5; i++) {
      Message m = new Message(config.runId(), "publisher-0", queue, i, "u-" + i);
      Value.Publication value = new Value.Publication(m);
      recorder.invoke("publisher-0", OpFunction.PUBLISH, value);
      recorder.complete("publisher-0", OpType.OK, OpFunction.CONFIRM, value, "");
      published.add(m);
    }
  }

  @Override
  public void stopPublishers() {
    phases.add("stopPublishers");
  }

  @Override
  public void stopConsumers() {
    phases.add("stopConsumers");
  }

  @Override
  public boolean drain() {
    phases.add("drain");
    String queue = config.queues().get(0).name();
    String process = "drain:" + queue;
    recorder.invoke(process, OpFunction.DRAIN, new Value.Drain(queue, 0));
    long tag = 1;
    for (Message m : published.subList(loseOne ? 1 : 0, published.size())) {
      recorder.observe(process, OpFunction.DELIVER, new Value.Delivery(m, false, tag++));
    }
    recorder.complete(process, OpType.OK, OpFunction.DRAIN, new Value.Drain(queue, tag - 1), "");
    return true;
  }

  @Override
  public List<String> queueNames() {
    return config.queues().stream().map(q -> q.name()).toList();
  }

  @Override
  public void close() {
    phases.add("close");
  }
}
