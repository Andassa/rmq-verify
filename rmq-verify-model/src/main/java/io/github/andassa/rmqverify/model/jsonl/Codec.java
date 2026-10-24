package io.github.andassa.rmqverify.model.jsonl;

import com.fasterxml.jackson.core.JsonGenerator;
import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.Value;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Mapping between domain objects and the JSON lines of schema version 1. */
final class Codec {

  private static final Set<String> HEADER_FIELDS =
      Set.of("kind", "schemaVersion", "runId", "queues", "attributes");
  private static final Set<String> OP_FIELDS =
      Set.of("kind", "id", "process", "type", "f", "t", "wall", "error", "value");
  private static final Set<String> MESSAGE_FIELDS =
      Set.of("runId", "publisher", "queue", "seq", "uuid");

  private Codec() {}

  static String encodeHeader(HistoryHeader header) throws IOException {
    StringWriter out = new StringWriter();
    try (JsonGenerator g = JsonTree.FACTORY.createGenerator(out)) {
      g.writeStartObject();
      g.writeStringField("kind", "header");
      g.writeNumberField("schemaVersion", header.schemaVersion());
      g.writeStringField("runId", header.runId());
      g.writeObjectFieldStart("queues");
      for (Map.Entry<String, QueueKind> queue : header.queues().entrySet()) {
        g.writeStringField(queue.getKey(), queue.getValue().name());
      }
      g.writeEndObject();
      g.writeObjectFieldStart("attributes");
      for (Map.Entry<String, String> attribute : header.attributes().entrySet()) {
        g.writeStringField(attribute.getKey(), attribute.getValue());
      }
      g.writeEndObject();
      g.writeEndObject();
    }
    return out.toString();
  }

  static String encodeOperation(Operation op) throws IOException {
    StringWriter out = new StringWriter();
    try (JsonGenerator g = JsonTree.FACTORY.createGenerator(out)) {
      g.writeStartObject();
      g.writeStringField("kind", "op");
      g.writeNumberField("id", op.id());
      g.writeStringField("process", op.process());
      g.writeStringField("type", op.type().name());
      g.writeStringField("f", op.function().name());
      g.writeNumberField("t", op.timestampNanos());
      g.writeStringField("wall", op.wallClock().toString());
      g.writeStringField("error", op.error());
      g.writeFieldName("value");
      writeValue(g, op.value());
      g.writeEndObject();
    }
    return out.toString();
  }

  private static void writeValue(JsonGenerator g, Value value) throws IOException {
    g.writeStartObject();
    switch (value) {
      case Value.Publication p -> {
        g.writeStringField("type", "publication");
        writeMessage(g, p.msg());
      }
      case Value.Delivery d -> {
        g.writeStringField("type", "delivery");
        writeMessage(g, d.msg());
        g.writeBooleanField("redelivered", d.redelivered());
        g.writeNumberField("tag", d.deliveryTag());
      }
      case Value.CorruptDelivery c -> {
        g.writeStringField("type", "corrupt");
        g.writeStringField("queue", c.queue());
        g.writeStringField("digest", c.digest());
        g.writeBooleanField("redelivered", c.redelivered());
        g.writeNumberField("tag", c.deliveryTag());
      }
      case Value.Drain d -> {
        g.writeStringField("type", "drain");
        g.writeStringField("queue", d.queue());
        g.writeNumberField("delivered", d.delivered());
      }
      case Value.Fault f -> {
        g.writeStringField("type", "fault");
        g.writeNumberField("index", f.index());
        g.writeStringField("nemesis", f.nemesis());
        writeStrings(g, "targets", f.targets());
        g.writeStringField("detail", f.detail());
      }
      case Value.Probe p -> {
        g.writeStringField("type", "probe");
        g.writeNumberField("round", p.round());
        g.writeStringField("observer", p.observer());
        g.writeStringField("subject", p.subject());
        g.writeStringField("leader", p.leader());
        writeStrings(g, "running", p.runningNodes());
        writeStrings(g, "partitions", p.partitions());
      }
    }
    g.writeEndObject();
  }

  private static void writeMessage(JsonGenerator g, Message m) throws IOException {
    g.writeObjectFieldStart("msg");
    g.writeStringField("runId", m.runId());
    g.writeStringField("publisher", m.publisherId());
    g.writeStringField("queue", m.queue());
    g.writeNumberField("seq", m.sequence());
    g.writeStringField("uuid", m.uuid());
    g.writeEndObject();
  }

  private static void writeStrings(JsonGenerator g, String name, List<String> values)
      throws IOException {
    g.writeArrayFieldStart(name);
    for (String value : values) {
      g.writeString(value);
    }
    g.writeEndArray();
  }

  static HistoryHeader decodeHeader(Fields f) throws HistoryFormatException {
    f.allowOnly(HEADER_FIELDS);
    requireKind(f, "header");
    long version = f.integer("schemaVersion");
    if (version != HistoryHeader.CURRENT_SCHEMA_VERSION) {
      throw new HistoryFormatException(f.line(), "unsupported schemaVersion " + version);
    }
    TreeMap<String, QueueKind> queues = new TreeMap<>();
    for (Map.Entry<String, String> queue : f.stringMap("queues").entrySet()) {
      queues.put(queue.getKey(), parseEnum(QueueKind.class, queue.getValue(), f.line()));
    }
    try {
      return new HistoryHeader((int) version, f.string("runId"), queues, f.stringMap("attributes"));
    } catch (IllegalArgumentException e) {
      throw new HistoryFormatException(f.line(), e.getMessage(), e);
    }
  }

  static Operation decodeOperation(Fields f) throws HistoryFormatException {
    f.allowOnly(OP_FIELDS);
    requireKind(f, "op");
    OpType type = parseEnum(OpType.class, f.string("type"), f.line());
    OpFunction function = parseEnum(OpFunction.class, f.string("f"), f.line());
    Instant wall = parseInstant(f.string("wall"), f.line());
    Value value = decodeValue(f.object("value"));
    try {
      return new Operation(
          f.integer("id"),
          f.string("process"),
          type,
          function,
          value,
          f.integer("t"),
          wall,
          f.string("error"));
    } catch (IllegalArgumentException e) {
      throw new HistoryFormatException(f.line(), e.getMessage(), e);
    }
  }

  private static Value decodeValue(Fields v) throws HistoryFormatException {
    String type = v.string("type");
    try {
      return switch (type) {
        case "publication" -> {
          v.allowOnly(Set.of("type", "msg"));
          yield new Value.Publication(decodeMessage(v.object("msg")));
        }
        case "delivery" -> {
          v.allowOnly(Set.of("type", "msg", "redelivered", "tag"));
          yield new Value.Delivery(
              decodeMessage(v.object("msg")), v.bool("redelivered"), v.integer("tag"));
        }
        case "corrupt" -> {
          v.allowOnly(Set.of("type", "queue", "digest", "redelivered", "tag"));
          yield new Value.CorruptDelivery(
              v.string("queue"), v.string("digest"), v.bool("redelivered"), v.integer("tag"));
        }
        case "drain" -> {
          v.allowOnly(Set.of("type", "queue", "delivered"));
          yield new Value.Drain(v.string("queue"), v.integer("delivered"));
        }
        case "fault" -> {
          v.allowOnly(Set.of("type", "index", "nemesis", "targets", "detail"));
          yield new Value.Fault(
              v.smallInteger("index"),
              v.string("nemesis"),
              v.strings("targets"),
              v.string("detail"));
        }
        case "probe" -> {
          v.allowOnly(
              Set.of("type", "round", "observer", "subject", "leader", "running", "partitions"));
          yield new Value.Probe(
              v.integer("round"),
              v.string("observer"),
              v.string("subject"),
              v.string("leader"),
              v.strings("running"),
              v.strings("partitions"));
        }
        default -> throw new HistoryFormatException(v.line(), "unknown value type '" + type + "'");
      };
    } catch (IllegalArgumentException e) {
      throw new HistoryFormatException(v.line(), e.getMessage(), e);
    }
  }

  private static Message decodeMessage(Fields m) throws HistoryFormatException {
    m.allowOnly(MESSAGE_FIELDS);
    return new Message(
        m.string("runId"),
        m.string("publisher"),
        m.string("queue"),
        m.integer("seq"),
        m.string("uuid"));
  }

  private static void requireKind(Fields f, String expected) throws HistoryFormatException {
    String kind = f.string("kind");
    if (!kind.equals(expected)) {
      throw new HistoryFormatException(
          f.line(), "expected kind '" + expected + "' but got '" + kind + "'");
    }
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, long line)
      throws HistoryFormatException {
    try {
      return Enum.valueOf(type, raw);
    } catch (IllegalArgumentException e) {
      throw new HistoryFormatException(
          line, "unknown " + type.getSimpleName() + " '" + raw + "'", e);
    }
  }

  private static Instant parseInstant(String raw, long line) throws HistoryFormatException {
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new HistoryFormatException(line, "invalid instant '" + raw + "'", e);
    }
  }
}
