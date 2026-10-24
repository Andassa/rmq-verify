package io.github.andassa.rmqverify.model;

import java.util.List;
import java.util.Optional;

/** Payload of an operation. Each function accepts exactly one family of values. */
public sealed interface Value
    permits Value.Publication,
        Value.Delivery,
        Value.CorruptDelivery,
        Value.Drain,
        Value.Fault,
        Value.Probe {

  /**
   * Tells whether this value may be carried by an operation of the given function.
   *
   * @param function the function of the operation
   * @return {@code true} if the combination is legal
   */
  boolean accepts(OpFunction function);

  /**
   * Returns the decoded message, when this value refers to one.
   *
   * @return the message, empty for drains, faults, probes and corrupt deliveries
   */
  default Optional<Message> message() {
    return Optional.empty();
  }

  /**
   * A message being published or confirmed.
   *
   * @param msg the published message
   */
  record Publication(Message msg) implements Value {
    /** Validates the message. */
    public Publication {
      Checks.notNull(msg, "msg");
    }

    @Override
    public boolean accepts(OpFunction function) {
      return function == OpFunction.PUBLISH || function == OpFunction.CONFIRM;
    }

    @Override
    public Optional<Message> message() {
      return Optional.of(msg);
    }
  }

  /**
   * A decoded message handed to a consumer, or acknowledged by it.
   *
   * @param msg the decoded message
   * @param redelivered value of the AMQP {@code redelivered} flag
   * @param deliveryTag AMQP delivery tag, or stream offset
   */
  record Delivery(Message msg, boolean redelivered, long deliveryTag) implements Value {
    /** Validates the components. */
    public Delivery {
      Checks.notNull(msg, "msg");
      Checks.nonNegative(deliveryTag, "deliveryTag");
    }

    @Override
    public boolean accepts(OpFunction function) {
      return function == OpFunction.DELIVER || function == OpFunction.ACK;
    }

    @Override
    public Optional<Message> message() {
      return Optional.of(msg);
    }
  }

  /**
   * A delivery whose payload could not be decoded.
   *
   * @param queue queue it was consumed from
   * @param digest SHA-256 of the raw payload, hex encoded
   * @param redelivered value of the AMQP {@code redelivered} flag
   * @param deliveryTag AMQP delivery tag, or stream offset
   */
  record CorruptDelivery(String queue, String digest, boolean redelivered, long deliveryTag)
      implements Value {
    /** Validates the components. */
    public CorruptDelivery {
      Checks.name(queue, "queue");
      Checks.name(digest, "digest");
      Checks.nonNegative(deliveryTag, "deliveryTag");
    }

    @Override
    public boolean accepts(OpFunction function) {
      return function == OpFunction.DELIVER || function == OpFunction.ACK;
    }
  }

  /**
   * The drain of one queue.
   *
   * @param queue drained queue
   * @param delivered number of messages received by the drain so far
   */
  record Drain(String queue, long delivered) implements Value {
    /** Validates the components. */
    public Drain {
      Checks.name(queue, "queue");
      Checks.nonNegative(delivered, "delivered");
    }

    @Override
    public boolean accepts(OpFunction function) {
      return function == OpFunction.DRAIN;
    }
  }

  /**
   * A fault injected or healed by the nemesis.
   *
   * @param index position of the fault in the plan; start and stop share it
   * @param nemesis name of the nemesis
   * @param targets affected nodes
   * @param detail human-readable parameters
   */
  record Fault(int index, String nemesis, List<String> targets, String detail) implements Value {
    /** Validates and copies the components. */
    public Fault {
      Checks.nonNegative(index, "index");
      Checks.name(nemesis, "nemesis");
      Checks.names(targets, "targets");
      targets = List.copyOf(targets);
      Checks.text(detail, "detail");
    }

    @Override
    public boolean accepts(OpFunction function) {
      return function == OpFunction.NEMESIS_START || function == OpFunction.NEMESIS_STOP;
    }
  }

  /**
   * The view of the cluster reported by one node.
   *
   * @param round probe round; probes of one round are taken concurrently
   * @param observer node whose management API answered
   * @param subject queue name, or {@code "cluster"} for membership-only probes
   * @param leader leader of {@code subject} according to the observer, empty if unknown
   * @param runningNodes nodes the observer believes are running
   * @param partitions nodes the observer reports as partitioned away
   */
  record Probe(
      long round,
      String observer,
      String subject,
      String leader,
      List<String> runningNodes,
      List<String> partitions)
      implements Value {
    /** Validates and copies the components. */
    public Probe {
      Checks.nonNegative(round, "round");
      Checks.name(observer, "observer");
      Checks.name(subject, "subject");
      Checks.text(leader, "leader");
      Checks.names(runningNodes, "runningNodes");
      runningNodes = List.copyOf(runningNodes);
      Checks.names(partitions, "partitions");
      partitions = List.copyOf(partitions);
    }

    @Override
    public boolean accepts(OpFunction function) {
      return function == OpFunction.PROBE;
    }
  }
}
