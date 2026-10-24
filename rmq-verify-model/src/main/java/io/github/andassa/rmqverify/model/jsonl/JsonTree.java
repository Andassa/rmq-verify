package io.github.andassa.rmqverify.model.jsonl;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses one JSON line into plain maps, lists, strings, longs and booleans. Floating point numbers,
 * {@code null}, duplicate keys and deep nesting are rejected: none of them is legal in the history
 * schema, and refusing them early keeps the decoder simple and safe.
 */
final class JsonTree {

  private static final int MAX_DEPTH = 6;

  static final JsonFactory FACTORY =
      JsonFactory.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(MAX_DEPTH)
                  .maxStringLength(64 * 1024)
                  .maxNumberLength(20)
                  .build())
          .build();

  private JsonTree() {}

  static Map<String, Object> parseObject(String line, long lineNumber)
      throws HistoryFormatException {
    try (JsonParser parser = FACTORY.createParser(line)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        throw new HistoryFormatException(lineNumber, "expected a JSON object");
      }
      Map<String, Object> object = readMembers(parser, lineNumber);
      if (parser.nextToken() != null) {
        throw new HistoryFormatException(lineNumber, "trailing content after JSON object");
      }
      return object;
    } catch (JsonProcessingException e) {
      throw new HistoryFormatException(lineNumber, "invalid JSON: " + e.getOriginalMessage(), e);
    } catch (HistoryFormatException e) {
      throw e;
    } catch (IOException e) {
      throw new HistoryFormatException(lineNumber, "unreadable JSON", e);
    }
  }

  private static Map<String, Object> readMembers(JsonParser parser, long lineNumber)
      throws IOException {
    Map<String, Object> object = new LinkedHashMap<>();
    while (parser.nextToken() == JsonToken.FIELD_NAME) {
      String name = parser.currentName();
      parser.nextToken();
      object.put(name, readValue(parser, lineNumber));
    }
    return object;
  }

  private static Object readValue(JsonParser parser, long lineNumber) throws IOException {
    JsonToken token = parser.currentToken();
    return switch (token) {
      case START_OBJECT -> readMembers(parser, lineNumber);
      case START_ARRAY -> readArray(parser, lineNumber);
      case VALUE_STRING -> parser.getText();
      case VALUE_NUMBER_INT -> readLong(parser, lineNumber);
      case VALUE_TRUE -> true;
      case VALUE_FALSE -> false;
      default -> throw new HistoryFormatException(lineNumber, "unsupported JSON token " + token);
    };
  }

  private static List<Object> readArray(JsonParser parser, long lineNumber) throws IOException {
    List<Object> array = new ArrayList<>();
    while (parser.nextToken() != JsonToken.END_ARRAY) {
      array.add(readValue(parser, lineNumber));
    }
    return array;
  }

  private static Long readLong(JsonParser parser, long lineNumber) throws IOException {
    if (parser.getNumberType() != JsonParser.NumberType.INT
        && parser.getNumberType() != JsonParser.NumberType.LONG) {
      throw new HistoryFormatException(lineNumber, "integer out of range: " + parser.getText());
    }
    return parser.getLongValue();
  }
}
