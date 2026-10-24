package io.github.andassa.rmqverify.model;

import static io.github.andassa.rmqverify.model.testkit.HistoryFixture.msg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.model.testkit.HistoryFixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HistoryTest {

  private static final HistoryHeader HEADER =
      HistoryHeader.of(HistoryFixture.RUN, Map.of("q", QueueKind.QUORUM), Map.of());

  @Test
  void pairsInvocationsWithCompletions() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Message m = msg("p1", "q", 0);
    Operation publish = f.publish(m);
    Operation confirm = f.confirm(m, OpType.OK);
    Operation deliver = f.deliver("c1", m, false, 1);
    Operation ack = f.ack("c1", m, false, 1, OpType.INFO);
    Operation drainInvoke = f.drainStart("q");
    Operation drainOk = f.drainEnd("q", OpType.OK, 0);
    History h = f.build();

    assertThat(h.size()).isEqualTo(7);
    assertThat(h.completionOf(publish)).contains(confirm);
    assertThat(h.invocationOf(confirm)).contains(publish);
    assertThat(h.completionOf(drainInvoke)).contains(drainOk);
    assertThat(h.invocationOf(drainOk)).contains(drainInvoke);
    assertThat(h.invocationOf(ack)).isPresent();
    assertThat(h.completionOf(confirm)).isEmpty();
    assertThat(h.invocationOf(publish)).isEmpty();
    assertThat(h.invocationOf(deliver)).isEmpty();
    assertThat(h.completionOf(deliver)).isEmpty();
    assertThat(h.byId(deliver.id())).contains(deliver);
    assertThat(h.byId(999)).isEmpty();
    assertThat(h.header()).isEqualTo(f.recorder().header());
    assertThat(h.toString()).contains("size=7");
  }

  @Test
  void pendingInvocationHasNoCompletion() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Operation publish = f.publish(msg("p1", "q", 0));
    History h = f.build();
    assertThat(h.completionOf(publish)).isEmpty();
  }

  @Test
  void foreignOperationHasNoPartner() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Message m = msg("p1", "q", 0);
    Operation publish = f.publish(m);
    f.confirm(m, OpType.OK);
    History h = f.build();
    Operation impostor =
        new Operation(
            publish.id(),
            "other",
            OpType.INVOKE,
            OpFunction.PUBLISH,
            publish.value(),
            0,
            Instant.EPOCH,
            "");
    assertThat(h.completionOf(impostor)).isEmpty();
    Operation unknownId =
        new Operation(
            77, "p1", OpType.INVOKE, OpFunction.PUBLISH, publish.value(), 0, Instant.EPOCH, "");
    assertThat(h.completionOf(unknownId)).isEmpty();
  }

  @Test
  void emptyHistoryIsValid() {
    History h = new History(HEADER, List.of());
    assertThat(h.operations()).isEmpty();
    assertThat(h)
        .isEqualTo(new History(HEADER, List.of()))
        .hasSameHashCodeAs(new History(HEADER, List.of()));
    assertThat(h).isNotEqualTo("history");
    assertThat(h)
        .isNotEqualTo(new History(HistoryHeader.of("other", Map.of(), Map.of()), List.of()));
  }

  @Test
  void historiesWithDifferentOperationsDiffer() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.publish(msg("p1", "q", 0));
    assertThat(f.build()).isNotEqualTo(new History(f.recorder().header(), List.of()));
  }

  @Test
  void rejectsNonIncreasingIdentifiers() {
    Operation a = publishOp(1, 0, msg("p1", "q", 0));
    Operation b = publishOp(1, 1, msg("p1", "q", 1));
    assertThatThrownBy(() -> new History(HEADER, List.of(a, b)))
        .isInstanceOf(InvalidHistoryException.class)
        .hasMessageContaining("identifiers must strictly increase")
        .extracting(e -> ((InvalidHistoryException) e).operationId())
        .isEqualTo(1L);
  }

  @Test
  void rejectsDecreasingTimestamps() {
    Operation a = publishOp(0, 10, msg("p1", "q", 0));
    Operation b = publishOp(1, 9, msg("p1", "q", 1));
    assertThatThrownBy(() -> new History(HEADER, List.of(a, b)))
        .hasMessageContaining("timestamps must not decrease");
    Operation c = publishOp(1, 10, msg("p1", "q", 1));
    assertThat(new History(HEADER, List.of(a, c)).size()).isEqualTo(2);
  }

  @Test
  void rejectsCompletionWithoutInvocation() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.confirm(msg("p1", "q", 0), OpType.OK);
    assertThatThrownBy(f::build).hasMessageContaining("OK CONFIRM without INVOKE");
  }

  @Test
  void rejectsCompletionFromAnotherProcess() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Message m = msg("p1", "q", 0);
    f.publish(m);
    f.record("p2", OpType.OK, OpFunction.CONFIRM, new Value.Publication(m));
    assertThatThrownBy(f::build).hasMessageContaining("without INVOKE");
  }

  @Test
  void rejectsDoubleCompletion() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Message m = msg("p1", "q", 0);
    f.publish(m);
    f.confirm(m, OpType.OK);
    f.confirm(m, OpType.OK);
    assertThatThrownBy(f::build).hasMessageContaining("without INVOKE");
  }

  @Test
  void rejectsDuplicatePendingInvocation() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.drainStart("q");
    f.drainStart("q");
    assertThatThrownBy(f::build).hasMessageContaining("duplicate pending invocation q");
  }

  @Test
  void rejectsCompletionWithDifferentValue() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Message m = msg("p1", "q", 0);
    Value.Delivery delivery = new Value.Delivery(m, false, 1);
    f.record("c1", OpType.INVOKE, OpFunction.ACK, delivery);
    f.record("c1", OpType.OK, OpFunction.ACK, new Value.Delivery(m, true, 1));
    assertThatThrownBy(f::build).hasMessageContaining("differs from its invocation");
  }

  @Test
  void drainCompletionMayReportACount() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.drainStart("q");
    f.drainEnd("q", OpType.OK, 42);
    assertThat(f.build().size()).isEqualTo(2);
  }

  @Test
  void corruptDeliveriesCanBeAcknowledged() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Value.CorruptDelivery corrupt = new Value.CorruptDelivery("q", "d", false, 4);
    f.record("c1", OpType.INVOKE, OpFunction.ACK, corrupt);
    Operation ok = f.record("c1", OpType.OK, OpFunction.ACK, corrupt);
    History h = f.build();
    assertThat(h.invocationOf(ok)).isPresent();
  }

  @Test
  void rejectsForeignRunPublication() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.publish(new Message("other-run", "p1", "q", 0, "u"));
    assertThatThrownBy(f::build).hasMessageContaining("foreign run other-run");
  }

  @Test
  void rejectsUndeclaredQueues() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.publish(msg("p1", "nope", 0));
    assertThatThrownBy(f::build).hasMessageContaining("queue not declared in header: nope");
    HistoryFixture g = HistoryFixture.quorum("q");
    g.drainStart("nope");
    assertThatThrownBy(g::build).hasMessageContaining("queue not declared in header: nope");
  }

  @Test
  void rejectsReusedUuid() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.publish(new Message(HistoryFixture.RUN, "p1", "q", 0, "same"));
    f.publish(new Message(HistoryFixture.RUN, "p1", "q", 1, "same"));
    assertThatThrownBy(f::build).hasMessageContaining("uuid published twice: same");
  }

  @Test
  void rejectsNonIncreasingSequences() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.publish(msg("p1", "q", 5));
    f.publish(new Message(HistoryFixture.RUN, "p1", "q", 5, "other"));
    assertThatThrownBy(f::build).hasMessageContaining("sequence 5 does not follow 5 for q");
  }

  @Test
  void sequencesAreIndependentPerPublisherAndQueue() {
    HistoryFixture f = HistoryFixture.quorum("q", "r");
    f.publish(msg("p1", "q", 5));
    f.publish(msg("p2", "q", 0));
    f.publish(msg("p1", "r", 0));
    f.publish(msg("p1", "q", 6));
    assertThat(f.build().size()).isEqualTo(4);
  }

  private static Operation publishOp(long id, long t, Message m) {
    return new Operation(
        id,
        "p1",
        OpType.INVOKE,
        OpFunction.PUBLISH,
        new Value.Publication(m),
        t,
        Instant.EPOCH,
        "");
  }
}
