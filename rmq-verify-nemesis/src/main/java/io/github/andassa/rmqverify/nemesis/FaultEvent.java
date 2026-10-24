package io.github.andassa.rmqverify.nemesis;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One fault of a plan, with every random choice already made so that it replays identically.
 *
 * @param index position in the plan
 * @param nemesis name of the nemesis
 * @param startMillis offset from the start of the run
 * @param durationMillis how long the fault stays active before it is healed
 * @param targets nodes chosen by the planner
 * @param params nemesis-specific parameters, as strings
 */
public record FaultEvent(
    int index,
    String nemesis,
    long startMillis,
    long durationMillis,
    List<String> targets,
    SortedMap<String, String> params) {

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
  private static final Pattern PARAM = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{0,31}");

  /** Validates the event and copies its collections. */
  public FaultEvent {
    if (index < 0 || startMillis < 0 || durationMillis <= 0 || durationMillis > 3_600_000) {
      throw new IllegalArgumentException("invalid timing for fault " + index);
    }
    if (nemesis == null || !NAME.matcher(nemesis).matches()) {
      throw new IllegalArgumentException("invalid nemesis name: " + nemesis);
    }
    targets = List.copyOf(targets);
    if (targets.size() > 16) {
      throw new IllegalArgumentException("too many targets");
    }
    if (params.size() > 16) {
      throw new IllegalArgumentException("too many params");
    }
    for (var entry : params.entrySet()) {
      if (!PARAM.matcher(entry.getKey()).matches() || entry.getValue().length() > 64) {
        throw new IllegalArgumentException("invalid param " + entry.getKey());
      }
    }
    params = Collections.unmodifiableSortedMap(new TreeMap<>(params));
  }

  /**
   * Returns the end of the fault relative to the start of the run.
   *
   * @return {@code startMillis + durationMillis}
   */
  public long endMillis() {
    return startMillis + durationMillis;
  }

  /**
   * Reads an integer parameter.
   *
   * @param name parameter name
   * @return its value
   */
  public int intParam(String name) {
    String value = params.get(name);
    if (value == null) {
      throw new IllegalArgumentException("fault " + index + " lacks parameter " + name);
    }
    return Integer.parseInt(value);
  }

  /**
   * Returns a human-readable description of the parameters.
   *
   * @return for example {@code targets=[rabbit1] latencyMs=200}
   */
  public String describe() {
    StringBuilder text = new StringBuilder("targets=").append(targets);
    params.forEach((k, v) -> text.append(' ').append(k).append('=').append(v));
    return text.toString();
  }
}
