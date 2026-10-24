package io.github.andassa.rmqverify.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, validated sequence of operations. Construction fails with {@link
 * InvalidHistoryException} if any invariant of {@code docs/METHODOLOGY.md} section 2 is broken.
 */
public final class History {

  private final HistoryHeader header;
  private final List<Operation> operations;
  private final long[] ids;
  private final int[] partner;

  /**
   * Builds and validates a history.
   *
   * @param header metadata of the run
   * @param operations operations in recording order
   * @throws InvalidHistoryException if an invariant is violated
   */
  public History(HistoryHeader header, List<Operation> operations) {
    this.header = Checks.notNull(header, "header");
    this.operations = List.copyOf(Checks.notNull(operations, "operations"));
    this.ids = new long[this.operations.size()];
    this.partner = new int[this.operations.size()];
    Arrays.fill(partner, -1);
    validate();
  }

  /**
   * Returns the run metadata.
   *
   * @return the header
   */
  public HistoryHeader header() {
    return header;
  }

  /**
   * Returns the operations in recording order.
   *
   * @return an unmodifiable list
   */
  public List<Operation> operations() {
    return operations;
  }

  /**
   * Returns the number of operations.
   *
   * @return the size of the history
   */
  public int size() {
    return operations.size();
  }

  /**
   * Looks an operation up by identifier.
   *
   * @param id operation identifier
   * @return the operation, empty if absent
   */
  public Optional<Operation> byId(long id) {
    int index = Arrays.binarySearch(ids, id);
    return index >= 0 ? Optional.of(operations.get(index)) : Optional.empty();
  }

  /**
   * Returns the completion of an invocation.
   *
   * @param invoke an {@code INVOKE} operation of this history
   * @return its completion, empty if the invocation is pending or not part of this history
   */
  public Optional<Operation> completionOf(Operation invoke) {
    return invoke.isInvoke() ? partnerOf(invoke) : Optional.empty();
  }

  /**
   * Returns the invocation closed by a completion.
   *
   * @param completion a completion operation of this history
   * @return the matching invocation, empty if the argument is not a completion of this history
   */
  public Optional<Operation> invocationOf(Operation completion) {
    return completion.isCompletion() ? partnerOf(completion) : Optional.empty();
  }

  private Optional<Operation> partnerOf(Operation operation) {
    int index = Arrays.binarySearch(ids, operation.id());
    if (index < 0 || !operations.get(index).equals(operation) || partner[index] < 0) {
      return Optional.empty();
    }
    return Optional.of(operations.get(partner[index]));
  }

  private void validate() {
    Map<PendingKey, Integer> pending = new HashMap<>();
    Map<String, Long> lastSequence = new HashMap<>();
    Set<String> publishedUuids = new HashSet<>();
    long previousTimestamp = Long.MIN_VALUE;
    for (int i = 0; i < operations.size(); i++) {
      Operation op = operations.get(i);
      if (i > 0 && op.id() <= ids[i - 1]) {
        throw new InvalidHistoryException(op.id(), "identifiers must strictly increase");
      }
      if (op.timestampNanos() < previousTimestamp) {
        throw new InvalidHistoryException(op.id(), "timestamps must not decrease");
      }
      ids[i] = op.id();
      previousTimestamp = op.timestampNanos();
      if (op.isInvoke()) {
        validateInvocation(op, i, pending, lastSequence, publishedUuids);
      } else if (op.isCompletion()) {
        validateCompletion(op, i, pending);
      }
    }
  }

  private void validateInvocation(
      Operation op,
      int index,
      Map<PendingKey, Integer> pending,
      Map<String, Long> lastSequence,
      Set<String> publishedUuids) {
    if (op.value() instanceof Value.Publication publication) {
      validatePublication(op, publication.msg(), lastSequence, publishedUuids);
    }
    if (op.value() instanceof Value.Drain drain) {
      requireDeclaredQueue(op, drain.queue());
    }
    PendingKey key = PendingKey.of(op, op.function());
    if (pending.putIfAbsent(key, index) != null) {
      throw new InvalidHistoryException(op.id(), "duplicate pending invocation " + key.key());
    }
  }

  private void validatePublication(
      Operation op, Message msg, Map<String, Long> lastSequence, Set<String> publishedUuids) {
    if (!msg.runId().equals(header.runId())) {
      throw new InvalidHistoryException(op.id(), "publication from foreign run " + msg.runId());
    }
    requireDeclaredQueue(op, msg.queue());
    if (!publishedUuids.add(msg.uuid())) {
      throw new InvalidHistoryException(op.id(), "uuid published twice: " + msg.uuid());
    }
    String stream = msg.publisherId() + '\u0000' + msg.queue();
    Long previous = lastSequence.put(stream, msg.sequence());
    if (previous != null && msg.sequence() <= previous) {
      throw new InvalidHistoryException(
          op.id(),
          "sequence " + msg.sequence() + " does not follow " + previous + " for " + msg.queue());
    }
  }

  private void requireDeclaredQueue(Operation op, String queue) {
    if (!header.queues().containsKey(queue)) {
      throw new InvalidHistoryException(op.id(), "queue not declared in header: " + queue);
    }
  }

  private void validateCompletion(Operation op, int index, Map<PendingKey, Integer> pending) {
    OpFunction invoked = op.function() == OpFunction.CONFIRM ? OpFunction.PUBLISH : op.function();
    Integer invokeIndex = pending.remove(PendingKey.of(op, invoked));
    if (invokeIndex == null) {
      throw new InvalidHistoryException(
          op.id(), op.type() + " " + op.function() + " without INVOKE");
    }
    Value invokedValue = operations.get(invokeIndex).value();
    boolean consistent =
        op.value() instanceof Value.Drain drain
            ? drain.queue().equals(((Value.Drain) invokedValue).queue())
            : op.value().equals(invokedValue);
    if (!consistent) {
      throw new InvalidHistoryException(op.id(), "completion value differs from its invocation");
    }
    partner[index] = invokeIndex;
    partner[invokeIndex] = index;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof History that
        && header.equals(that.header)
        && operations.equals(that.operations);
  }

  @Override
  public int hashCode() {
    return 31 * header.hashCode() + operations.hashCode();
  }

  @Override
  public String toString() {
    return "History[runId=" + header.runId() + ", size=" + operations.size() + "]";
  }

  /** Identity of an invocation awaiting its completion. */
  private record PendingKey(String process, OpFunction function, String key) {

    /** Only invocable functions reach this method, so the value is one of four families. */
    static PendingKey of(Operation op, OpFunction invocationFunction) {
      Value value = op.value();
      String key;
      if (value instanceof Value.Publication p) {
        key = p.msg().uuid();
      } else if (value instanceof Value.Delivery d) {
        key = d.deliveryTag() + ":" + d.msg().uuid();
      } else if (value instanceof Value.CorruptDelivery c) {
        key = c.deliveryTag() + ":" + c.digest();
      } else {
        key = ((Value.Drain) value).queue();
      }
      return new PendingKey(op.process(), invocationFunction, key);
    }
  }
}
