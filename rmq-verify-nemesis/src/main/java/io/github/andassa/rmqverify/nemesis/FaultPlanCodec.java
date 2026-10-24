package io.github.andassa.rmqverify.nemesis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes {@code plan.json}. Plans are untrusted input on replay: the size is bounded,
 * unknown fields are rejected, records are rebuilt through their validating constructors and every
 * nemesis name must be registered. Jackson only binds to the plan records; no polymorphic typing.
 */
public final class FaultPlanCodec {

  /** Largest accepted plan file. */
  public static final long MAX_BYTES = 1024 * 1024;

  private final ObjectMapper mapper =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private final NemesisRegistry registry;

  /**
   * Creates the codec.
   *
   * @param registry nemeses accepted in plans
   */
  public FaultPlanCodec(NemesisRegistry registry) {
    this.registry = registry;
    mapper
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(4096).build());
  }

  /**
   * Serialises a plan; equal plans give identical bytes.
   *
   * @param plan the plan
   * @return pretty-printed JSON ending with a newline
   */
  public String write(FaultPlan plan) {
    try {
      return mapper.writeValueAsString(plan) + "\n";
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a valid plan is always serialisable", e);
    }
  }

  /**
   * Writes a plan to a file.
   *
   * @param plan the plan
   * @param path destination
   * @throws IOException if the file cannot be written
   */
  public void write(FaultPlan plan, Path path) throws IOException {
    Files.writeString(path, write(plan), StandardCharsets.UTF_8);
  }

  /**
   * Parses and validates a plan.
   *
   * @param json plan content
   * @return the plan
   * @throws InvalidPlanException if the content is not a valid plan
   */
  public FaultPlan read(String json) throws InvalidPlanException {
    if (json.length() > MAX_BYTES) {
      throw new InvalidPlanException("plan larger than " + MAX_BYTES + " bytes");
    }
    FaultPlan plan;
    try {
      plan = mapper.readValue(json, FaultPlan.class);
    } catch (JsonProcessingException e) {
      throw new InvalidPlanException("malformed plan: " + e.getOriginalMessage(), e);
    }
    for (FaultEvent event : plan.events()) {
      if (registry.byName(event.nemesis()).isEmpty()) {
        throw new InvalidPlanException("unknown nemesis " + event.nemesis());
      }
    }
    return plan;
  }

  /**
   * Reads a plan file.
   *
   * @param path the file
   * @return the plan
   * @throws IOException if the file cannot be read
   * @throws InvalidPlanException if the content is not a valid plan
   */
  public FaultPlan read(Path path) throws IOException, InvalidPlanException {
    if (Files.size(path) > MAX_BYTES) {
      throw new InvalidPlanException(path + " is larger than " + MAX_BYTES + " bytes");
    }
    return read(Files.readString(path, StandardCharsets.UTF_8));
  }
}
