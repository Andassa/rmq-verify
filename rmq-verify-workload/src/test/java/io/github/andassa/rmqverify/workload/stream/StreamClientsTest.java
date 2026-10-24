package io.github.andassa.rmqverify.workload.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rabbitmq.stream.Address;
import com.rabbitmq.stream.ConfirmationHandler;
import com.rabbitmq.stream.ConfirmationStatus;
import com.rabbitmq.stream.Constants;
import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.ConsumerBuilder;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.MessageBuilder;
import com.rabbitmq.stream.MessageHandler;
import com.rabbitmq.stream.NoOffsetException;
import com.rabbitmq.stream.Producer;
import com.rabbitmq.stream.ProducerBuilder;
import com.rabbitmq.stream.StreamException;
import com.rabbitmq.stream.StreamStats;
import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.model.testkit.ManualClock;
import io.github.andassa.rmqverify.workload.BrokerAccess;
import io.github.andassa.rmqverify.workload.MessageCodec;
import io.github.andassa.rmqverify.workload.Pacer;
import io.github.andassa.rmqverify.workload.PublisherPlan;
import io.github.andassa.rmqverify.workload.QueueSpec;
import io.github.andassa.rmqverify.workload.WorkloadConfig;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StreamClientsTest {

  private final ManualClock clock = new ManualClock();
  private final HistoryRecorder recorder =
      new HistoryRecorder(
          HistoryHeader.of("run-1", Map.of("rmqv.s", QueueKind.STREAM), Map.of()), clock, op -> {});
  private final Environment environment = mock(Environment.class);

  @Test
  void addressResolverMapsAdvertisedNodesToTheirProxies() {
    BrokerAccess access =
        new BrokerAccess(
            "127.0.0.1",
            List.of(1),
            Map.of("rabbit2", 25552),
            "rmqv",
            "u",
            "p",
            java.util.Optional.empty());
    assertThat(StreamEnvironments.resolve(access, new Address("rabbit2", 5552)))
        .isEqualTo(new Address("127.0.0.1", 25552));
    assertThat(StreamEnvironments.resolve(access, new Address("elsewhere", 5552)))
        .isEqualTo(new Address("elsewhere", 5552));
  }

  @Test
  void clientSideCodesAreIndeterminateAndBrokerCodesAreFailures() {
    assertThat(StreamPublisher.outcomeOf(Constants.CODE_PUBLISH_CONFIRM_TIMEOUT))
        .isEqualTo(OpType.INFO);
    assertThat(StreamPublisher.outcomeOf(Constants.CODE_PRODUCER_NOT_AVAILABLE))
        .isEqualTo(OpType.INFO);
    assertThat(StreamPublisher.outcomeOf(Constants.CODE_PRODUCER_CLOSED)).isEqualTo(OpType.INFO);
    assertThat(StreamPublisher.outcomeOf(Constants.CODE_MESSAGE_ENQUEUEING_FAILED))
        .isEqualTo(OpType.INFO);
    assertThat(StreamPublisher.outcomeOf(Constants.RESPONSE_CODE_ACCESS_REFUSED))
        .isEqualTo(OpType.FAIL);
  }

  @Test
  void publisherRecordsEachConfirmationOutcome() {
    ProducerBuilder builder = mock(ProducerBuilder.class, RETURNS_SELF);
    Producer producer = mock(Producer.class);
    MessageBuilder messages = mock(MessageBuilder.class, RETURNS_SELF);
    com.rabbitmq.stream.Message message = mock(com.rabbitmq.stream.Message.class);
    when(environment.producerBuilder()).thenReturn(builder);
    when(builder.build()).thenThrow(new StreamException("no leader")).thenReturn(producer);
    when(producer.messageBuilder()).thenReturn(messages);
    when(messages.build()).thenReturn(message);
    Deque<Object> outcomes =
        new ArrayDeque<>(
            List.of(
                true,
                Constants.CODE_PUBLISH_CONFIRM_TIMEOUT,
                Constants.RESPONSE_CODE_STREAM_DOES_NOT_EXIST,
                "silent",
                "throw"));
    org.mockito.Mockito.doAnswer(
            inv -> {
              ConfirmationHandler handler = inv.getArgument(1);
              Object next = outcomes.poll();
              if (next instanceof Boolean) {
                handler.handle(new ConfirmationStatus(message, true, Constants.RESPONSE_CODE_OK));
              } else if (next instanceof Short code) {
                handler.handle(new ConfirmationStatus(message, false, code));
              } else if ("throw".equals(next)) {
                throw new StreamException("producer closed");
              }
              return null;
            })
        .when(producer)
        .send(any(), any());
    publish(7);
    List<Operation> confirms =
        recorder.snapshot().operations().stream()
            .filter(o -> o.function() == OpFunction.CONFIRM)
            .toList();
    assertThat(confirms)
        .extracting(Operation::type)
        .containsExactly(OpType.OK, OpType.INFO, OpType.FAIL, OpType.INFO, OpType.INFO);
    assertThat(confirms.get(3).error()).startsWith("no confirmation within");
  }

  private void publish(int attempts) {
    WorkloadConfig config =
        new WorkloadConfig(
            "run-1",
            5,
            List.of(new QueueSpec("rmqv.s", QueueKind.STREAM, 0)),
            1,
            1,
            100,
            200,
            200,
            1,
            Duration.ofMillis(50),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1));
    AtomicInteger pauses = new AtomicInteger();
    StreamPublisher[] holder = new StreamPublisher[1];
    Pacer pacer =
        d -> {
          if (pauses.incrementAndGet() > attempts) {
            holder[0].stop();
          }
        };
    holder[0] =
        new StreamPublisher(
            "publisher-0",
            "run-1",
            PublisherPlan.forAll(config).get(0),
            environment,
            recorder,
            Duration.ofMillis(50),
            pacer);
    assertThat(holder[0].process()).isEqualTo("publisher-0");
    holder[0].run();
  }

  @Test
  void consumerRecordsOffsetsAsTags() {
    ConsumerBuilder builder = mock(ConsumerBuilder.class, RETURNS_SELF);
    when(environment.consumerBuilder()).thenReturn(builder);
    MessageHandler[] handler = new MessageHandler[1];
    when(builder.messageHandler(any()))
        .thenAnswer(
            inv -> {
              handler[0] = inv.getArgument(0);
              return builder;
            });
    Consumer consumer = mock(Consumer.class);
    when(builder.build()).thenThrow(new StreamException("not yet")).thenReturn(consumer);
    StreamConsumer[] holder = new StreamConsumer[1];
    AtomicInteger pauses = new AtomicInteger();
    holder[0] =
        new StreamConsumer(
            "consumer-0-0",
            "rmqv.s",
            environment,
            recorder,
            d -> {
              if (pauses.incrementAndGet() == 2) {
                handler[0].handle(context(7), body(new Message("run-1", "p", "rmqv.s", 0, "u")));
                handler[0].handle(context(8), bytes("junk"));
                holder[0].stop();
              }
            });
    holder[0].run();
    List<Operation> deliveries = recorder.snapshot().operations();
    assertThat(deliveries).hasSize(2);
    assertThat(((Value.Delivery) deliveries.get(0).value()).deliveryTag()).isEqualTo(7);
    assertThat(deliveries.get(1).value()).isInstanceOf(Value.CorruptDelivery.class);
    org.mockito.Mockito.verify(consumer).close();
  }

  @Test
  void drainerReadsUpToTheCommittedOffset() throws Exception {
    StreamStats stats = mock(StreamStats.class);
    when(environment.queryStreamStats("rmqv.s"))
        .thenThrow(new StreamException("leader moving"))
        .thenReturn(stats);
    when(stats.committedOffset()).thenReturn(1L);
    ConsumerBuilder builder = mock(ConsumerBuilder.class, RETURNS_SELF);
    when(environment.consumerBuilder()).thenReturn(builder);
    MessageHandler[] handler = new MessageHandler[1];
    when(builder.messageHandler(any()))
        .thenAnswer(
            inv -> {
              handler[0] = inv.getArgument(0);
              return builder;
            });
    when(builder.build()).thenReturn(mock(Consumer.class));
    AtomicInteger polls = new AtomicInteger();
    StreamDrainer drainer =
        new StreamDrainer(
            environment,
            recorder,
            clock,
            d -> {
              clock.advance(d);
              if (polls.incrementAndGet() == 2) {
                handler[0].handle(context(0), body(new Message("run-1", "p", "rmqv.s", 0, "a")));
                handler[0].handle(context(1), body(new Message("run-1", "p", "rmqv.s", 1, "b")));
              }
            },
            Duration.ofSeconds(10),
            Duration.ofSeconds(1));
    assertThat(drainer.drain("rmqv.s", "drain:rmqv.s")).isTrue();
    Operation last = recorder.snapshot().operations().getLast();
    assertThat(last.type()).isEqualTo(OpType.OK);
    assertThat(((Value.Drain) last.value()).delivered()).isEqualTo(2);
  }

  @Test
  void drainerHandlesEmptyUnavailableAndUnreachedStreams() throws Exception {
    StreamDrainer drainer =
        new StreamDrainer(
            environment,
            recorder,
            clock,
            clock::advance,
            Duration.ofSeconds(2),
            Duration.ofSeconds(1));
    StreamStats empty = mock(StreamStats.class);
    when(empty.committedOffset()).thenThrow(new NoOffsetException("no committed offset"));
    when(empty.committedChunkId()).thenThrow(new NoOffsetException("no chunk"));
    when(environment.queryStreamStats("rmqv.s")).thenReturn(empty);
    assertThat(drainer.drain("rmqv.s", "drain:a")).isTrue();
    org.mockito.Mockito.reset(environment);
    org.mockito.Mockito.doThrow(new StreamException("down"))
        .when(environment)
        .queryStreamStats("rmqv.s");
    assertThat(drainer.drain("rmqv.s", "drain:b")).isFalse();
    StreamStats stats = mock(StreamStats.class);
    when(stats.committedOffset()).thenReturn(10L);
    org.mockito.Mockito.reset(environment);
    when(environment.queryStreamStats("rmqv.s")).thenReturn(stats);
    ConsumerBuilder builder = mock(ConsumerBuilder.class, RETURNS_SELF);
    when(environment.consumerBuilder()).thenReturn(builder);
    when(builder.build()).thenReturn(mock(Consumer.class));
    assertThat(drainer.drain("rmqv.s", "drain:c")).isFalse();
    org.mockito.Mockito.doThrow(new StreamException("cannot subscribe")).when(builder).build();
    assertThat(drainer.drain("rmqv.s", "drain:d")).isFalse();
    assertThat(
            recorder.snapshot().operations().stream()
                .filter(o -> o.isCompletion())
                .map(Operation::error))
        .containsExactly(
            "",
            "stream statistics unavailable",
            "offset 10 not reached within PT2S",
            "drain failed: cannot subscribe");
  }

  @Test
  void withoutACommittedOffsetTheDrainReadsTheLastChunkUntilQuiet() throws Exception {
    StreamStats stats = mock(StreamStats.class);
    when(stats.committedOffset()).thenThrow(new NoOffsetException("not reported by RabbitMQ 3.13"));
    when(stats.committedChunkId()).thenReturn(1L);
    when(environment.queryStreamStats("rmqv.s")).thenReturn(stats);
    ConsumerBuilder builder = mock(ConsumerBuilder.class, RETURNS_SELF);
    when(environment.consumerBuilder()).thenReturn(builder);
    MessageHandler[] handler = new MessageHandler[1];
    when(builder.messageHandler(any()))
        .thenAnswer(
            inv -> {
              handler[0] = inv.getArgument(0);
              return builder;
            });
    when(builder.build()).thenReturn(mock(Consumer.class));
    AtomicInteger polls = new AtomicInteger();
    StreamDrainer drainer =
        new StreamDrainer(
            environment,
            recorder,
            clock,
            d -> {
              clock.advance(d);
              if (polls.incrementAndGet() == 1) {
                for (int offset = 0; offset < 3; offset++) {
                  handler[0].handle(
                      context(offset),
                      body(new Message("run-1", "p", "rmqv.s", offset, "m" + offset)));
                }
              }
            },
            Duration.ofSeconds(10),
            Duration.ofSeconds(1));
    assertThat(drainer.drain("rmqv.s", "drain:rmqv.s")).isTrue();
    Operation last = recorder.snapshot().operations().getLast();
    assertThat(((Value.Drain) last.value()).delivered()).isEqualTo(3);
    assertThat(polls.get()).isGreaterThanOrEqualTo(5);
  }

  private static MessageHandler.Context context(long offset) {
    MessageHandler.Context context = mock(MessageHandler.Context.class);
    when(context.offset()).thenReturn(offset);
    return context;
  }

  private static com.rabbitmq.stream.Message body(Message m) {
    com.rabbitmq.stream.Message message = mock(com.rabbitmq.stream.Message.class);
    when(message.getBodyAsBinary()).thenReturn(MessageCodec.encode(m, 200));
    return message;
  }

  private static com.rabbitmq.stream.Message bytes(String raw) {
    com.rabbitmq.stream.Message message = mock(com.rabbitmq.stream.Message.class);
    when(message.getBodyAsBinary())
        .thenReturn(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return message;
  }
}
