package io.github.andassa.rmqverify.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DomainRecordsTest {

  private static final Message MSG = new Message("run", "p1", "q1", 0, "u1");
  private static final Instant WALL = Instant.EPOCH;

  @Test
  void opTypeCompletionFlags() {
    assertThat(OpType.INVOKE.isCompletion()).isFalse();
    assertThat(OpType.OK.isCompletion()).isTrue();
    assertThat(OpType.FAIL.isCompletion()).isTrue();
    assertThat(OpType.INFO.isCompletion()).isTrue();
  }

  @Test
  void opFunctionCategories() {
    assertThat(OpFunction.PUBLISH.isInvocable()).isTrue();
    assertThat(OpFunction.ACK.isInvocable()).isTrue();
    assertThat(OpFunction.DRAIN.isInvocable()).isTrue();
    assertThat(OpFunction.CONFIRM.isInvocable()).isFalse();
    assertThat(OpFunction.DELIVER.isInvocable()).isFalse();
    assertThat(OpFunction.DELIVER.isObservation()).isTrue();
    assertThat(OpFunction.NEMESIS_START.isObservation()).isTrue();
    assertThat(OpFunction.NEMESIS_STOP.isObservation()).isTrue();
    assertThat(OpFunction.PROBE.isObservation()).isTrue();
    assertThat(OpFunction.PUBLISH.isObservation()).isFalse();
    assertThat(OpFunction.CONFIRM.isObservation()).isFalse();
    assertThat(OpFunction.ACK.isObservation()).isFalse();
    assertThat(OpFunction.DRAIN.isObservation()).isFalse();
    assertThat(OpFunction.PUBLISH.completedBy()).contains(OpFunction.CONFIRM);
    assertThat(OpFunction.ACK.completedBy()).contains(OpFunction.ACK);
    assertThat(OpFunction.DRAIN.completedBy()).contains(OpFunction.DRAIN);
    assertThat(OpFunction.DELIVER.completedBy()).isEmpty();
  }

  @Test
  void queueKindLogFlag() {
    assertThat(QueueKind.STREAM.isLog()).isTrue();
    assertThat(QueueKind.QUORUM.isLog()).isFalse();
    assertThat(QueueKind.CLASSIC.isLog()).isFalse();
  }

  @Test
  void messageRejectsInvalidComponents() {
    assertThatThrownBy(() -> new Message(" ", "p", "q", 0, "u"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runId");
    assertThatThrownBy(() -> new Message("r", null, "q", 0, "u"))
        .hasMessageContaining("publisherId");
    assertThatThrownBy(() -> new Message("r", "p", "q", -1, "u")).hasMessageContaining("sequence");
    assertThatThrownBy(() -> new Message("r", "p", "q\n", 0, "u"))
        .hasMessageContaining("control characters");
    assertThatThrownBy(() -> new Message("r", "p", "q", 0, "u".repeat(257)))
        .hasMessageContaining("exceeds");
    assertThat(new Message("r", "p", "q", 0, "u".repeat(256)).uuid()).hasSize(256);
  }

  @Test
  void valuesAcceptOnlyTheirFunctions() {
    Value publication = new Value.Publication(MSG);
    Value delivery = new Value.Delivery(MSG, false, 1);
    Value corrupt = new Value.CorruptDelivery("q1", "abc", true, 2);
    Value drain = new Value.Drain("q1", 3);
    Value fault = new Value.Fault(0, "kill", List.of("rabbit1"), "");
    Value probe = new Value.Probe(0, "rabbit1", "cluster", "", List.of(), List.of());
    for (OpFunction f : OpFunction.values()) {
      assertThat(publication.accepts(f))
          .isEqualTo(f == OpFunction.PUBLISH || f == OpFunction.CONFIRM);
      assertThat(delivery.accepts(f)).isEqualTo(f == OpFunction.DELIVER || f == OpFunction.ACK);
      assertThat(corrupt.accepts(f)).isEqualTo(f == OpFunction.DELIVER || f == OpFunction.ACK);
      assertThat(drain.accepts(f)).isEqualTo(f == OpFunction.DRAIN);
      assertThat(fault.accepts(f))
          .isEqualTo(f == OpFunction.NEMESIS_START || f == OpFunction.NEMESIS_STOP);
      assertThat(probe.accepts(f)).isEqualTo(f == OpFunction.PROBE);
    }
    assertThat(publication.message()).contains(MSG);
    assertThat(delivery.message()).contains(MSG);
    assertThat(corrupt.message()).isEmpty();
    assertThat(drain.message()).isEmpty();
  }

  @Test
  void valuesValidateComponents() {
    assertThatThrownBy(() -> new Value.Publication(null)).hasMessageContaining("msg");
    assertThatThrownBy(() -> new Value.Delivery(MSG, false, -1))
        .hasMessageContaining("deliveryTag");
    assertThatThrownBy(() -> new Value.Delivery(null, false, 0)).hasMessageContaining("msg");
    assertThatThrownBy(() -> new Value.CorruptDelivery("q", "", false, 0))
        .hasMessageContaining("digest");
    assertThatThrownBy(() -> new Value.CorruptDelivery("q", "d", false, -1))
        .hasMessageContaining("deliveryTag");
    assertThatThrownBy(() -> new Value.Drain("q", -1)).hasMessageContaining("delivered");
    assertThatThrownBy(() -> new Value.Fault(-1, "n", List.of(), "")).hasMessageContaining("index");
    assertThatThrownBy(() -> new Value.Fault(0, "n", null, "")).hasMessageContaining("targets");
    assertThatThrownBy(() -> new Value.Fault(0, "n", List.of(), null))
        .hasMessageContaining("detail");
    assertThatThrownBy(() -> new Value.Fault(0, "n", Collections.nCopies(65, "x"), ""))
        .hasMessageContaining("more than 64");
    assertThatThrownBy(() -> new Value.Fault(0, "n", List.of(""), ""))
        .hasMessageContaining("targets entry");
    assertThatThrownBy(() -> new Value.Probe(-1, "o", "s", "", List.of(), List.of()))
        .hasMessageContaining("round");
    assertThatThrownBy(() -> new Value.Probe(0, "o", "s", "x".repeat(4097), List.of(), List.of()))
        .hasMessageContaining("leader");
    assertThatThrownBy(() -> new Value.Probe(0, "o", "s", "", List.of(" "), List.of()))
        .hasMessageContaining("runningNodes entry");
    assertThatThrownBy(() -> new Value.Probe(0, "o", "s", "", List.of(), List.of("")))
        .hasMessageContaining("partitions entry");
    assertThat(new Value.Fault(0, "n", Collections.nCopies(64, "x"), "d".repeat(4096)).targets())
        .hasSize(64);
  }

  @Test
  void faultTargetsAreDefensivelyCopied() {
    List<String> targets = new ArrayList<>(List.of("rabbit1"));
    Value.Fault fault = new Value.Fault(0, "kill", targets, "");
    targets.add("rabbit2");
    assertThat(fault.targets()).containsExactly("rabbit1");
  }

  @Test
  void operationRejectsIllegalCombinations() {
    Value publication = new Value.Publication(MSG);
    assertThatThrownBy(
            () -> op(OpType.INVOKE, OpFunction.DELIVER, new Value.Delivery(MSG, false, 0)))
        .hasMessageContaining("never invoked");
    assertThatThrownBy(() -> op(OpType.INVOKE, OpFunction.CONFIRM, publication))
        .hasMessageContaining("never invoked");
    assertThatThrownBy(() -> op(OpType.OK, OpFunction.PUBLISH, publication))
        .hasMessageContaining("completes with CONFIRM");
    assertThatThrownBy(() -> op(OpType.OK, OpFunction.DRAIN, publication))
        .hasMessageContaining("cannot carry Publication");
    assertThatThrownBy(
            () -> new Operation(-1, "p", OpType.OK, OpFunction.CONFIRM, publication, 0, WALL, ""))
        .hasMessageContaining("id");
    assertThatThrownBy(
            () -> new Operation(0, "p", OpType.OK, OpFunction.CONFIRM, publication, 0, null, ""))
        .hasMessageContaining("wallClock");
    assertThatThrownBy(
            () -> new Operation(0, "p", null, OpFunction.CONFIRM, publication, 0, WALL, ""))
        .hasMessageContaining("type");
    assertThatThrownBy(() -> new Operation(0, "p", OpType.OK, null, publication, 0, WALL, ""))
        .hasMessageContaining("function");
    assertThatThrownBy(
            () -> new Operation(0, "p", OpType.OK, OpFunction.CONFIRM, null, 0, WALL, ""))
        .hasMessageContaining("value");
  }

  @ParameterizedTest
  @EnumSource(
      value = OpType.class,
      names = {"OK", "FAIL", "INFO"})
  void completionsAndObservations(OpType type) {
    Operation confirm = op(type, OpFunction.CONFIRM, new Value.Publication(MSG));
    assertThat(confirm.isCompletion()).isTrue();
    assertThat(confirm.isInvoke()).isFalse();
    Operation deliver = op(type, OpFunction.DELIVER, new Value.Delivery(MSG, false, 0));
    assertThat(deliver.isCompletion()).isFalse();
    assertThat(deliver.isInvoke()).isFalse();
  }

  @Test
  void invokeIsNotACompletion() {
    Operation publish = op(OpType.INVOKE, OpFunction.PUBLISH, new Value.Publication(MSG));
    assertThat(publish.isInvoke()).isTrue();
    assertThat(publish.isCompletion()).isFalse();
  }

  private static Operation op(OpType type, OpFunction function, Value value) {
    return new Operation(0, "p1", type, function, value, 0, WALL, "");
  }
}
