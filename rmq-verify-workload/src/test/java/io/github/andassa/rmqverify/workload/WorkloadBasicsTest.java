package io.github.andassa.rmqverify.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.QueueKind;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkloadBasicsTest {

  static WorkloadConfig config(QueueKind kind, int publishers, long seed) {
    return new WorkloadConfig(
        "run-1",
        seed,
        List.of(new QueueSpec("rmqv.a", kind, 0), new QueueSpec("rmqv.b", kind, 0)),
        publishers,
        1,
        100,
        200,
        400,
        10,
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        Duration.ofMillis(300));
  }

  @Test
  void codecRoundTripsAndPads() {
    Message m = new Message("run-1", "publisher-0", "rmqv.a", 42, "u-1");
    byte[] small = MessageCodec.encode(m, 10);
    byte[] padded = MessageCodec.encode(m, 1000);
    assertThat(padded).hasSize(1000);
    assertThat(small.length).isLessThan(200);
    assertThat(MessageCodec.decode(small)).contains(m);
    assertThat(MessageCodec.decode(padded)).contains(m);
  }

  @Test
  void codecRejectsEverythingElse() {
    for (String payload :
        List.of(
            "",
            "[]",
            "{}",
            "not json",
            "{\"runId\":\"r\"}",
            "{\"runId\":\"r\",\"publisher\":\"p\",\"queue\":\"q\",\"seq\":1,\"uuid\":\"u\",\"pad\":\"\",\"x\":1}",
            "{\"runId\":\"r\",\"publisher\":\"p\",\"queue\":\"q\",\"seq\":\"1\",\"uuid\":\"u\",\"pad\":\"\"}",
            "{\"runId\":\"r\",\"publisher\":\"p\",\"queue\":\"q\",\"seq\":1,\"uuid\":\"u\",\"pad\":1}",
            "{\"runId\":\"r\",\"publisher\":\"p\",\"queue\":\"q\",\"seq\":-1,\"uuid\":\"u\",\"pad\":\"\"}",
            "{\"runId\":\"r\",\"publisher\":\"p\",\"queue\":\"q\",\"seq\":99999999999999999999,\"uuid\":\"u\",\"pad\":\"\"}",
            "{\"runId\":\"r\",\"publisher\":\"p\",\"queue\":\"q\",\"seq\":1,\"uuid\":\"u\",\"pad\":\"\"} trailing",
            "{\"runId\":\"r\",\"publisher\":\"p\",\"queue\":\"q\",\"seq\":1,\"uuid\":\"u\",\"pad\":{}}")) {
      assertThat(MessageCodec.decode(payload.getBytes(StandardCharsets.UTF_8)))
          .as(payload)
          .isEmpty();
    }
    assertThat(MessageCodec.decode(null)).isEmpty();
    assertThat(MessageCodec.decode(new byte[(1 << 20) + 5000])).isEmpty();
    assertThat(MessageCodec.digest("abc".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void plansAreDeterministicPerSeedAndPublisher() {
    List<PublisherPlan.Planned> first =
        draw(PublisherPlan.forAll(config(QueueKind.QUORUM, 3, 7)).get(2), 50);
    List<PublisherPlan.Planned> again =
        draw(PublisherPlan.forAll(config(QueueKind.QUORUM, 3, 7)).get(2), 50);
    List<PublisherPlan.Planned> other =
        draw(PublisherPlan.forAll(config(QueueKind.QUORUM, 3, 8)).get(2), 50);
    assertThat(first).isEqualTo(again).isNotEqualTo(other);
    assertThat(first)
        .allSatisfy(
            p -> {
              assertThat(p.size()).isBetween(200, 400);
              assertThat(p.pauseNanos()).isBetween(0L, 10_000_000_000L);
              assertThat(java.util.UUID.fromString(p.uuid()).version()).isEqualTo(4);
              assertThat(java.util.UUID.fromString(p.uuid()).variant()).isEqualTo(2);
            });
    Map<String, List<Long>> sequences = new java.util.HashMap<>();
    first.forEach(
        p -> sequences.computeIfAbsent(p.queue(), q -> new ArrayList<>()).add(p.sequence()));
    sequences
        .values()
        .forEach(s -> assertThat(s).isSorted().doesNotHaveDuplicates().startsWith(0L));
  }

  @Test
  void fixedSizePayloads() {
    WorkloadConfig fixed =
        new WorkloadConfig(
            "run-1",
            1,
            List.of(new QueueSpec("rmqv.a", QueueKind.STREAM, 0)),
            1,
            1,
            10,
            300,
            300,
            1,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1));
    assertThat(draw(PublisherPlan.forAll(fixed).get(0), 5))
        .extracting(PublisherPlan.Planned::size)
        .containsOnly(300);
    assertThat(fixed.kind()).isEqualTo(QueueKind.STREAM);
  }

  @Test
  void configurationIsValidated() {
    QueueSpec q = new QueueSpec("rmqv.a", QueueKind.QUORUM, 0);
    Duration s = Duration.ofSeconds(1);
    assertThatThrownBy(
            () -> new WorkloadConfig("bad id!", 1, List.of(q), 1, 1, 1, 200, 200, 1, s, s, s))
        .hasMessageContaining("runId");
    assertThatThrownBy(() -> new WorkloadConfig("r", 1, List.of(), 1, 1, 1, 200, 200, 1, s, s, s))
        .hasMessageContaining("queues");
    assertThatThrownBy(
            () ->
                new WorkloadConfig(
                    "r",
                    1,
                    List.of(q, new QueueSpec("rmqv.b", QueueKind.STREAM, 0)),
                    1,
                    1,
                    1,
                    200,
                    200,
                    1,
                    s,
                    s,
                    s))
        .hasMessageContaining("same kind");
    assertThatThrownBy(() -> new WorkloadConfig("r", 1, List.of(q), 0, 1, 1, 200, 200, 1, s, s, s))
        .hasMessageContaining("publishers");
    assertThatThrownBy(
            () -> new WorkloadConfig("r", 1, List.of(q), 1, 1, 0.01, 200, 200, 1, s, s, s))
        .hasMessageContaining("ratePerPublisher");
    assertThatThrownBy(
            () -> new WorkloadConfig("r", 1, List.of(q), 1, 1, Double.NaN, 200, 200, 1, s, s, s))
        .hasMessageContaining("ratePerPublisher");
    assertThatThrownBy(() -> new WorkloadConfig("r", 1, List.of(q), 1, 1, 1, 300, 200, 1, s, s, s))
        .hasMessageContaining("payloadMaxBytes");
    assertThatThrownBy(
            () -> new WorkloadConfig("r", 1, List.of(q), 1, 1, 1, 200, 200, 1, Duration.ZERO, s, s))
        .hasMessageContaining("confirmTimeout");
    assertThatThrownBy(
            () -> new WorkloadConfig("r", 1, List.of(q), 1, 1, 1, 200, 200, 1, s, null, s))
        .hasMessageContaining("drainTimeout");
    assertThatThrownBy(
            () -> new WorkloadConfig("r", 1, List.of(q), 1, 1, 1, 200, 200, 1, s, s, s.negated()))
        .hasMessageContaining("drainQuiet");
    assertThatThrownBy(() -> new QueueSpec("other.q", QueueKind.QUORUM, 0))
        .hasMessageContaining("rmqv");
    assertThatThrownBy(() -> new QueueSpec("rmqv.q", null, 0)).hasMessageContaining("kind");
    assertThatThrownBy(() -> new QueueSpec("rmqv.q", QueueKind.STREAM, 10))
        .hasMessageContaining("maxLength");
    assertThatThrownBy(() -> new QueueSpec("rmqv.q", QueueKind.CLASSIC, -1))
        .hasMessageContaining("maxLength");
  }

  @Test
  void brokerAccessHidesThePassword() {
    BrokerAccess access =
        new BrokerAccess(
            "127.0.0.1",
            List.of(1),
            Map.of("rabbit1", 2),
            "v",
            "u",
            "hunter2",
            java.util.Optional.empty());
    assertThat(access.toString()).doesNotContain("hunter2");
    assertThatThrownBy(
            () ->
                new BrokerAccess(
                    "h", List.of(), Map.of(), "v", "u", "p", java.util.Optional.empty()))
        .hasMessageContaining("AMQP port");
    assertThatThrownBy(
            () ->
                new BrokerAccess(
                    " ", List.of(1), Map.of(), "v", "u", "p", java.util.Optional.empty()))
        .hasMessageContaining("required");
  }

  @Test
  void backoffDoublesUpToTheCapAndResets() {
    Backoff backoff = new Backoff(Duration.ofMillis(100), Duration.ofMillis(350));
    assertThat(
            List.of(
                backoff.nextDelay(), backoff.nextDelay(), backoff.nextDelay(), backoff.nextDelay()))
        .containsExactly(
            Duration.ofMillis(100),
            Duration.ofMillis(200),
            Duration.ofMillis(350),
            Duration.ofMillis(350));
    backoff.reset();
    assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(100));
  }

  private static List<PublisherPlan.Planned> draw(PublisherPlan plan, int n) {
    List<PublisherPlan.Planned> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(plan.next());
    }
    return out;
  }
}
