package io.github.andassa.rmqverify.model.jsonl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Typed, strict access to the members of a parsed JSON object. */
final class Fields {

  private final Map<String, Object> members;
  private final long line;

  Fields(Map<String, Object> members, long line) {
    this.members = members;
    this.line = line;
  }

  void allowOnly(Set<String> names) throws HistoryFormatException {
    for (String name : members.keySet()) {
      if (!names.contains(name)) {
        throw new HistoryFormatException(line, "unknown field '" + name + "'");
      }
    }
  }

  String string(String name) throws HistoryFormatException {
    return as(name, String.class, "a string");
  }

  long integer(String name) throws HistoryFormatException {
    return as(name, Long.class, "an integer");
  }

  int smallInteger(String name) throws HistoryFormatException {
    long value = integer(name);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new HistoryFormatException(line, "field '" + name + "' does not fit an int");
    }
    return (int) value;
  }

  boolean bool(String name) throws HistoryFormatException {
    return as(name, Boolean.class, "a boolean");
  }

  Fields object(String name) throws HistoryFormatException {
    Map<String, Object> nested = castMap(as(name, Map.class, "an object"));
    return new Fields(nested, line);
  }

  List<String> strings(String name) throws HistoryFormatException {
    List<?> raw = as(name, List.class, "an array");
    List<String> result = new ArrayList<>(raw.size());
    for (Object element : raw) {
      if (!(element instanceof String s)) {
        throw new HistoryFormatException(line, "field '" + name + "' must contain strings only");
      }
      result.add(s);
    }
    return result;
  }

  SortedMap<String, String> stringMap(String name) throws HistoryFormatException {
    Fields nested = object(name);
    TreeMap<String, String> result = new TreeMap<>();
    for (String key : nested.members.keySet()) {
      result.put(key, nested.string(key));
    }
    return result;
  }

  long line() {
    return line;
  }

  private <T> T as(String name, Class<T> type, String description) throws HistoryFormatException {
    Object value = members.get(name);
    if (value == null) {
      throw new HistoryFormatException(line, "missing field '" + name + "'");
    }
    if (!type.isInstance(value)) {
      throw new HistoryFormatException(line, "field '" + name + "' must be " + description);
    }
    return type.cast(value);
  }

  @SuppressWarnings("unchecked") // JsonTree only produces maps with String keys.
  private static Map<String, Object> castMap(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }
}
