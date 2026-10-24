package io.github.andassa.rmqverify.workload;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import io.github.andassa.rmqverify.model.Message;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Encodes message identities into JSON payloads padded to a target size, and decodes them back.
 * Decoding never throws: anything that is not exactly a payload of this format is reported as
 * corrupt, with the SHA-256 of its bytes.
 */
public final class MessageCodec {

  private static final int MAX_PAYLOAD = (1 << 20) + 4096;
  private static final JsonFactory FACTORY =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(2)
                  .maxStringLength(MAX_PAYLOAD)
                  .build())
          .build();

  private MessageCodec() {}

  /**
   * Encodes a message, padding the payload to at least {@code size} bytes.
   *
   * @param message the identity to carry
   * @param size target payload size
   * @return UTF-8 JSON bytes
   */
  public static byte[] encode(Message message, int size) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(size);
    try (JsonGenerator g = FACTORY.createGenerator(out)) {
      g.writeStartObject();
      g.writeStringField("runId", message.runId());
      g.writeStringField("publisher", message.publisherId());
      g.writeStringField("queue", message.queue());
      g.writeNumberField("seq", message.sequence());
      g.writeStringField("uuid", message.uuid());
      g.writeStringField("pad", "");
      g.writeEndObject();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    int missing = size - out.size();
    if (missing <= 0) {
      return out.toByteArray();
    }
    String json = out.toString(java.nio.charset.StandardCharsets.UTF_8);
    String padded = json.replace("\"pad\":\"\"", "\"pad\":\"" + "x".repeat(missing) + "\"");
    return padded.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * Decodes a payload.
   *
   * @param payload raw message body
   * @return the identity, empty if the payload is not a valid rmq-verify payload
   */
  public static Optional<Message> decode(byte[] payload) {
    if (payload == null || payload.length > MAX_PAYLOAD) {
      return Optional.empty();
    }
    try (JsonParser p = FACTORY.createParser(payload)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        return Optional.empty();
      }
      Map<String, Object> fields = new HashMap<>();
      while (p.nextToken() == JsonToken.FIELD_NAME) {
        String name = p.currentName();
        JsonToken value = p.nextToken();
        if (value == JsonToken.VALUE_STRING) {
          fields.put(name, p.getText());
        } else if (value == JsonToken.VALUE_NUMBER_INT
            && p.getNumberType() != JsonParser.NumberType.BIG_INTEGER) {
          fields.put(name, p.getLongValue());
        } else {
          return Optional.empty();
        }
      }
      boolean complete =
          p.nextToken() == null
              && fields.size() == 6
              && strings(fields, "pad", "runId", "publisher", "queue", "uuid")
              && fields.get("seq") instanceof Long;
      if (!complete) {
        return Optional.empty();
      }
      return Optional.of(
          new Message(
              (String) fields.get("runId"),
              (String) fields.get("publisher"),
              (String) fields.get("queue"),
              (Long) fields.get("seq"),
              (String) fields.get("uuid")));
    } catch (IOException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static boolean strings(Map<String, Object> fields, String... names) {
    for (String name : names) {
      if (!(fields.get(name) instanceof String)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Fingerprints a payload that could not be decoded.
   *
   * @param payload raw bytes
   * @return lowercase hexadecimal SHA-256
   */
  public static String digest(byte[] payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
    }
  }
}
