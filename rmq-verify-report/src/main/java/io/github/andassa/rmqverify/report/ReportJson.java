package io.github.andassa.rmqverify.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes {@code report.json}. Only the report records are bound; no polymorphic typing.
 */
public final class ReportJson {

  private static final long MAX_BYTES = 64L * 1024 * 1024;

  private final ObjectMapper mapper =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          // Derived accessors such as Anomaly.isViolation() are not part of the format.
          .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
          .build();

  /**
   * Serialises a summary.
   *
   * @param summary the run
   * @return pretty-printed JSON
   */
  public String write(RunSummary summary) {
    try {
      return mapper.writeValueAsString(summary) + "\n";
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a run summary is always serialisable", e);
    }
  }

  /**
   * Writes a summary to a file.
   *
   * @param summary the run
   * @param path destination
   * @throws IOException if the file cannot be written
   */
  public void write(RunSummary summary, Path path) throws IOException {
    Files.writeString(path, write(summary), StandardCharsets.UTF_8);
  }

  /**
   * Reads a summary.
   *
   * @param path a {@code report.json}
   * @return the summary
   * @throws IOException if the file is unreadable, too large or invalid
   */
  public RunSummary read(Path path) throws IOException {
    if (Files.size(path) > MAX_BYTES) {
      throw new IOException(path + " is larger than " + MAX_BYTES + " bytes");
    }
    return mapper.readValue(Files.readString(path, StandardCharsets.UTF_8), RunSummary.class);
  }
}
