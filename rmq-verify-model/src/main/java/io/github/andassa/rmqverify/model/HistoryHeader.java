package io.github.andassa.rmqverify.model;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Metadata stored on the first line of a history file.
 *
 * @param schemaVersion version of the JSONL format; must equal {@link #CURRENT_SCHEMA_VERSION}
 * @param runId identifier of the run; every publication of the run carries it
 * @param queues queues exercised by the run and their type
 * @param attributes free-form context (RabbitMQ version, seed, topology), sorted by key
 */
public record HistoryHeader(
    int schemaVersion,
    String runId,
    SortedMap<String, QueueKind> queues,
    SortedMap<String, String> attributes) {

  /** The only schema version this code reads and writes. */
  public static final int CURRENT_SCHEMA_VERSION = 1;

  private static final int MAX_ENTRIES = 256;

  /** Validates the components and stores unmodifiable sorted copies of the maps. */
  public HistoryHeader {
    if (schemaVersion != CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "unsupported schemaVersion " + schemaVersion + ", expected " + CURRENT_SCHEMA_VERSION);
    }
    Checks.name(runId, "runId");
    validate(queues, "queues");
    validate(attributes, "attributes");
    attributes.values().forEach(v -> Checks.text(v, "attribute value"));
    queues = Collections.unmodifiableSortedMap(new TreeMap<>(queues));
    attributes = Collections.unmodifiableSortedMap(new TreeMap<>(attributes));
  }

  /**
   * Creates a header with the current schema version.
   *
   * @param runId identifier of the run
   * @param queues queues and their type
   * @param attributes free-form context
   * @return the header
   */
  public static HistoryHeader of(
      String runId, Map<String, QueueKind> queues, Map<String, String> attributes) {
    return new HistoryHeader(
        CURRENT_SCHEMA_VERSION, runId, new TreeMap<>(queues), new TreeMap<>(attributes));
  }

  private static void validate(SortedMap<String, ?> source, String field) {
    Checks.notNull(source, field);
    if (source.size() > MAX_ENTRIES) {
      throw new IllegalArgumentException(field + " has more than " + MAX_ENTRIES + " entries");
    }
    source.forEach(
        (key, value) -> {
          Checks.name(key, field + " key");
          Checks.notNull(value, field);
        });
  }
}
