package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Everything the history says about one published message. */
public final class MessageRecord {

  private final Message message;
  private final Operation publish;
  private Operation confirm;
  private final List<Operation> deliveries = new ArrayList<>();
  private final List<Operation> acks = new ArrayList<>();

  MessageRecord(Message message, Operation publish) {
    this.message = message;
    this.publish = publish;
  }

  /**
   * Returns the published message.
   *
   * @return the message
   */
  public Message message() {
    return message;
  }

  /**
   * Returns the {@code INVOKE PUBLISH} operation.
   *
   * @return the invocation
   */
  public Operation publish() {
    return publish;
  }

  /**
   * Returns the {@code CONFIRM} completion.
   *
   * @return the completion, empty while pending
   */
  public Optional<Operation> confirm() {
    return Optional.ofNullable(confirm);
  }

  /**
   * Returns the outcome of the publication; a pending publication is indeterminate.
   *
   * @return {@code OK}, {@code FAIL} or {@code INFO}
   */
  public OpType outcome() {
    return confirm == null ? OpType.INFO : confirm.type();
  }

  /**
   * Returns every delivery of this message, to consumers and to the drain, in history order.
   *
   * @return an unmodifiable view
   */
  public List<Operation> allDeliveries() {
    return Collections.unmodifiableList(deliveries);
  }

  /**
   * Returns the completions of acknowledgements of this message.
   *
   * @return an unmodifiable view
   */
  public List<Operation> acks() {
    return Collections.unmodifiableList(acks);
  }

  /**
   * Tells whether any delivery was observed.
   *
   * @return {@code true} if delivered at least once
   */
  public boolean delivered() {
    return !deliveries.isEmpty();
  }

  void confirmedBy(Operation completion) {
    this.confirm = completion;
  }

  void deliveredBy(Operation delivery) {
    deliveries.add(delivery);
  }

  void acknowledgedBy(Operation completion) {
    acks.add(completion);
  }
}
