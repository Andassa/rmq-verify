package io.github.andassa.rmqverify.workload.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.Return;
import com.rabbitmq.client.ReturnCallback;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.model.testkit.ManualClock;
import io.github.andassa.rmqverify.workload.MessageCodec;
import io.github.andassa.rmqverify.workload.Pacer;
import io.github.andassa.rmqverify.workload.PublisherPlan;
import io.github.andassa.rmqverify.workload.QueueSpec;
import io.github.andassa.rmqverify.workload.WorkloadConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AmqpClientsTest {

  private final ManualClock clock = new ManualClock();
  private final HistoryRecorder recorder =
      new HistoryRecorder(
          HistoryHeader.of("run-1", Map.of("rmqv.a", QueueKind.QUORUM), Map.of()), clock, op -> {});
  private final Channel channel = mock(Channel.class);
  private final Connection connection = mock(Connection.class);

  AmqpClientsTest() throws IOException {
    when(connection.createChannel()).thenReturn(channel);
  }

  /** Publisher whose plan has one queue and which stops after {@code publications} attempts. */
  private AmqpPublisher publisher(AmqpConnector connector, int publications) {
    WorkloadConfig config =
        new WorkloadConfig(
            "run-1",
            3,
            List.of(new QueueSpec("rmqv.a", QueueKind.QUORUM, 0)),
            1,
            1,
            100,
            200,
            200,
            1,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1));
    AtomicInteger pauses = new AtomicInteger();
    AmqpPublisher[] holder = new AmqpPublisher[1];
    Pacer pacer =
        d -> {
          if (pauses.incrementAndGet() > publications) {
            holder[0].stop();
          }
        };
    holder[0] =
        new AmqpPublisher(
            "publisher-0",
            "run-1",
            PublisherPlan.forAll(config).get(0),
            connector,
            recorder,
            Duration.ofSeconds(1),
            pacer);
    return holder[0];
  }

  private List<OpType> confirms() {
    return recorder.snapshot().operations().stream()
        .filter(o -> o.function() == OpFunction.CONFIRM)
        .map(Operation::type)
        .toList();
  }

  @Test
  void confirmsNacksAndTimeoutsAreRecordedPerMessage() throws Exception {
    when(channel.waitForConfirms(anyLong()))
        .thenReturn(true, false)
        .thenThrow(new TimeoutException())
        .thenReturn(true);
    publisher(name -> connection, 4).run();
    assertThat(confirms()).containsExactly(OpType.OK, OpType.FAIL, OpType.INFO, OpType.OK);
    verify(channel, org.mockito.Mockito.atLeastOnce()).confirmSelect();
    verify(connection, org.mockito.Mockito.atLeastOnce()).close(1_000);
    History h = recorder.snapshot();
    assertThat(
            h.operations().stream()
                .filter(o -> o.function() == OpFunction.PUBLISH)
                .map(o -> ((Value.Publication) o.value()).msg().sequence()))
        .containsExactly(0L, 1L, 2L, 3L);
  }

  @Test
  void returnedMessagesAreFailuresAndIoErrorsAreIndeterminate() throws Exception {
    ReturnCallback[] callback = new ReturnCallback[1];
    doAnswer(
            inv -> {
              callback[0] = inv.getArgument(0);
              return null;
            })
        .when(channel)
        .addReturnListener(any(ReturnCallback.class));
    AtomicInteger publishes = new AtomicInteger();
    doAnswer(
            inv -> {
              if (publishes.incrementAndGet() == 1) {
                callback[0].handle(
                    new Return(
                        312, "NO_ROUTE", "", "rmqv.a", new AMQP.BasicProperties(), new byte[0]));
              } else {
                throw new IOException("connection reset");
              }
              return null;
            })
        .when(channel)
        .basicPublish(eq(""), eq("rmqv.a"), eq(true), any(), any());
    when(channel.waitForConfirms(anyLong())).thenReturn(true);
    publisher(name -> connection, 2).run();
    assertThat(confirms()).containsExactly(OpType.FAIL, OpType.INFO);
    assertThat(
            recorder.snapshot().operations().stream()
                .filter(o -> o.function() == OpFunction.CONFIRM)
                .map(Operation::error))
        .containsExactly("returned: unroutable", "IOException: connection reset");
  }

  @Test
  void connectionFailuresPublishNothing() {
    publisher(
            name -> {
              throw new IOException("refused");
            },
            3)
        .run();
    assertThat(recorder.size()).isZero();
  }

  @Test
  void interruptionWhileWaitingIsIndeterminate() throws Exception {
    when(channel.waitForConfirms(anyLong())).thenThrow(new InterruptedException());
    publisher(name -> connection, 5).run();
    assertThat(confirms()).containsExactly(OpType.INFO);
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void consumerRecordsDeliveriesAndAckOutcomes() throws Exception {
    DeliverCallback[] deliver = new DeliverCallback[1];
    when(channel.basicConsume(
            eq("rmqv.a"), eq(false), any(DeliverCallback.class), any(CancelCallback.class)))
        .thenAnswer(
            inv -> {
              deliver[0] = inv.getArgument(2);
              CancelCallback cancel = inv.getArgument(3);
              cancel.handle("ctag");
              return "ctag";
            });
    AmqpConsumer[] consumer = new AmqpConsumer[1];
    Message m = new Message("run-1", "p", "rmqv.a", 0, "u-0");
    doThrow(new IOException("closed")).when(channel).basicAck(2, false);
    when(channel.isOpen())
        .thenAnswer(
            inv -> {
              deliver[0].handle(
                  "ctag",
                  new Delivery(
                      new Envelope(1, false, "", "rmqv.a"), null, MessageCodec.encode(m, 200)));
              deliver[0].handle(
                  "ctag",
                  new Delivery(
                      new Envelope(2, true, "", "rmqv.a"),
                      null,
                      "garbage".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
              consumer[0].stop();
              return true;
            });
    consumer[0] =
        new AmqpConsumer("consumer-0-0", "rmqv.a", 5, name -> connection, recorder, d -> {});
    assertThat(consumer[0].process()).isEqualTo("consumer-0-0");
    consumer[0].run();
    verify(channel).basicQos(5);
    List<Operation> ops = recorder.snapshot().operations();
    assertThat(ops)
        .extracting(Operation::function)
        .containsExactly(
            OpFunction.DELIVER,
            OpFunction.ACK,
            OpFunction.ACK,
            OpFunction.DELIVER,
            OpFunction.ACK,
            OpFunction.ACK);
    assertThat(ops.get(2).type()).isEqualTo(OpType.OK);
    assertThat(ops.get(3).value()).isInstanceOf(Value.CorruptDelivery.class);
    assertThat(((Value.CorruptDelivery) ops.get(3).value()).redelivered()).isTrue();
    assertThat(ops.get(5).type()).isEqualTo(OpType.INFO);
  }

  @Test
  void consumerRetriesAfterConnectionFailures() {
    AtomicInteger attempts = new AtomicInteger();
    AmqpConsumer[] consumer = new AmqpConsumer[1];
    consumer[0] =
        new AmqpConsumer(
            "consumer-0-0",
            "rmqv.a",
            1,
            name -> {
              if (attempts.incrementAndGet() == 3) {
                consumer[0].stop();
              }
              throw new TimeoutException("handshake");
            },
            recorder,
            d -> {});
    consumer[0].run();
    assertThat(attempts.get()).isEqualTo(3);
  }

  @Test
  void drainerCompletesAfterAQuietEmptyQueue() throws Exception {
    Message m = new Message("run-1", "p", "rmqv.a", 0, "u-0");
    when(channel.basicGet("rmqv.a", false))
        .thenReturn(
            new GetResponse(
                new Envelope(1, true, "", "rmqv.a"), null, MessageCodec.encode(m, 200), 0),
            (GetResponse) null);
    when(channel.queueDeclarePassive("rmqv.a"))
        .thenReturn(
            new AMQP.Queue.DeclareOk.Builder()
                .queue("rmqv.a")
                .messageCount(0)
                .consumerCount(1)
                .build(),
            new AMQP.Queue.DeclareOk.Builder()
                .queue("rmqv.a")
                .messageCount(0)
                .consumerCount(0)
                .build());
    AmqpDrainer drainer =
        new AmqpDrainer(
            name -> connection,
            recorder,
            clock,
            d -> clock.advance(d),
            Duration.ofSeconds(30),
            Duration.ofSeconds(1));
    assertThat(drainer.drain("rmqv.a", "drain:rmqv.a")).isTrue();
    Operation last = recorder.snapshot().operations().getLast();
    assertThat(last.type()).isEqualTo(OpType.OK);
    assertThat(((Value.Drain) last.value()).delivered()).isEqualTo(1);
  }

  @Test
  void drainerGivesUpWithInfoAtTheDeadline() throws Exception {
    AtomicInteger opens = new AtomicInteger();
    AmqpDrainer drainer =
        new AmqpDrainer(
            name -> {
              if (opens.incrementAndGet() % 2 == 1) {
                throw new IOException("node down");
              }
              return connection;
            },
            recorder,
            clock,
            d -> clock.advance(d),
            Duration.ofSeconds(3),
            Duration.ofSeconds(1));
    when(channel.basicGet(anyString(), anyBoolean())).thenReturn(null);
    when(channel.queueDeclarePassive("rmqv.a"))
        .thenReturn(
            new AMQP.Queue.DeclareOk.Builder()
                .queue("rmqv.a")
                .messageCount(5)
                .consumerCount(0)
                .build());
    assertThat(drainer.drain("rmqv.a", "drain:rmqv.a")).isFalse();
    Operation last = recorder.snapshot().operations().getLast();
    assertThat(last.type()).isEqualTo(OpType.INFO);
    assertThat(last.error()).contains("end criterion not met");
  }

  @Test
  void declarerUsesTheQueueTypeArguments() throws Exception {
    new QueueDeclarer(name -> connection)
        .declare(
            List.of(
                new QueueSpec("rmqv.q", QueueKind.QUORUM, 0),
                new QueueSpec("rmqv.c", QueueKind.CLASSIC, 10)));
    verify(channel)
        .queueDeclare(
            "rmqv.q",
            true,
            false,
            false,
            Map.of("x-queue-type", "quorum", "x-quorum-initial-group-size", 3));
    verify(channel)
        .queueDeclare(
            "rmqv.c",
            true,
            false,
            false,
            Map.of("x-queue-type", "classic", "x-max-length", 10L, "x-overflow", "drop-head"));
    QueueDeclarer failing =
        new QueueDeclarer(
            name -> {
              throw new IOException("refused");
            });
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> failing.declare(List.of(new QueueSpec("rmqv.s", QueueKind.STREAM, 0))))
        .hasMessageContaining("cannot declare");
  }

  @Test
  void connectorShufflesNodesWithoutRecovery() {
    io.github.andassa.rmqverify.workload.BrokerAccess access =
        new io.github.andassa.rmqverify.workload.BrokerAccess(
            "127.0.0.1", List.of(1, 2, 3), Map.of(), "rmqv", "u", "p");
    RabbitConnector connector = new RabbitConnector(access, 1);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> connector.open("test"))
        .isInstanceOf(IOException.class);
  }
}
