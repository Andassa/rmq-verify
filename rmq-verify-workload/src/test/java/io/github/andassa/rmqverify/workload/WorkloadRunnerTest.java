package io.github.andassa.rmqverify.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.ConsumerBuilder;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.NoOffsetException;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.NanoClock;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.QueueKind;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class WorkloadRunnerTest {

  private static final BrokerAccess ACCESS =
      new BrokerAccess("127.0.0.1", List.of(1), Map.of("rabbit1", 2), "rmqv", "u", "p");
  private static final Pacer FAST = d -> Thread.sleep(1);

  private static HistoryRecorder recorder(WorkloadConfig config) {
    Map<String, QueueKind> queues = new TreeMap<>();
    config.queues().forEach(q -> queues.put(q.name(), q.kind()));
    return new HistoryRecorder(
        HistoryHeader.of(config.runId(), queues, Map.of()), NanoClock.system(), op -> {});
  }

  @Test
  void amqpWorkloadRunsEveryPhase() throws Exception {
    Connection connection = mock(Connection.class);
    Channel channel = mock(Channel.class);
    when(connection.createChannel()).thenReturn(channel);
    when(channel.waitForConfirms(anyLong())).thenReturn(true);
    when(channel.isOpen()).thenReturn(true);
    when(channel.basicConsume(
            anyString(), anyBoolean(), any(DeliverCallback.class), any(CancelCallback.class)))
        .thenReturn("ctag");
    when(channel.basicGet(anyString(), anyBoolean())).thenReturn(null);
    when(channel.queueDeclarePassive(anyString()))
        .thenAnswer(
            inv ->
                new AMQP.Queue.DeclareOk.Builder()
                    .queue(inv.getArgument(0))
                    .messageCount(0)
                    .consumerCount(0)
                    .build());
    WorkloadConfig config = WorkloadBasicsTest.config(QueueKind.QUORUM, 2, 11);
    HistoryRecorder recorder = recorder(config);
    try (WorkloadRunner runner =
        new WorkloadRunner(
            config, ACCESS, name -> connection, recorder, NanoClock.system(), FAST, a -> null)) {
      runner.prepare();
      runner.startConsumers();
      runner.startPublishers();
      Thread.sleep(200);
      runner.stopPublishers();
      runner.stopConsumers();
      assertThat(runner.drain()).isTrue();
      assertThat(runner.queueNames()).containsExactly("rmqv.a", "rmqv.b");
    }
    History history = recorder.snapshot();
    assertThat(history.operations())
        .filteredOn(o -> o.function() == OpFunction.CONFIRM)
        .isNotEmpty();
    assertThat(history.operations()).filteredOn(o -> o.function() == OpFunction.DRAIN).hasSize(4);
  }

  @Test
  void streamWorkloadUsesTheEnvironment() throws Exception {
    Connection connection = mock(Connection.class);
    when(connection.createChannel()).thenReturn(mock(Channel.class));
    Environment environment = mock(Environment.class);
    ConsumerBuilder consumers = mock(ConsumerBuilder.class, RETURNS_SELF);
    when(environment.consumerBuilder()).thenReturn(consumers);
    when(consumers.build()).thenReturn(mock(Consumer.class));
    when(environment.producerBuilder()).thenThrow(new com.rabbitmq.stream.StreamException("down"));
    when(environment.queryStreamStats(anyString())).thenThrow(new NoOffsetException("empty"));
    WorkloadConfig config = WorkloadBasicsTest.config(QueueKind.STREAM, 1, 3);
    HistoryRecorder recorder = recorder(config);
    WorkloadRunner runner =
        new WorkloadRunner(
            config,
            ACCESS,
            name -> connection,
            recorder,
            NanoClock.system(),
            FAST,
            a -> environment);
    runner.prepare();
    runner.startConsumers();
    runner.startPublishers();
    Thread.sleep(100);
    runner.stopPublishers();
    runner.stopConsumers();
    assertThat(runner.drain()).isTrue();
    runner.close();
    org.mockito.Mockito.verify(environment).close();
  }

  @Test
  void publicConstructorUsesTheRealClients() {
    WorkloadConfig config = WorkloadBasicsTest.config(QueueKind.QUORUM, 1, 1);
    WorkloadRunner runner =
        new WorkloadRunner(
            config, ACCESS, name -> mock(Connection.class), recorder(config), NanoClock.system());
    runner.close();
    assertThat(runner.queueNames()).hasSize(2);
  }
}
