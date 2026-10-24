package io.github.andassa.rmqverify.report;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.Value;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-second time series derived from a history, for charts. Seconds are counted from the first
 * operation of the history. Immutable: every accessor returns a copy.
 */
public final class Series {

  /**
   * A fault window, in seconds from the origin.
   *
   * @param index fault index
   * @param nemesis nemesis name
   * @param startSeconds start offset
   * @param endSeconds end offset, the end of the history if never healed
   */
  public record Window(int index, String nemesis, double startSeconds, double endSeconds) {}

  private final int seconds;
  private final long[] confirmed;
  private final long[] failed;
  private final long[] indeterminate;
  private final long[] delivered;
  private final double[] latencyP50Millis;
  private final double[] latencyP99Millis;
  private final List<Window> faults;
  private final long originNanos;

  /** Counts are confirmed, failed, indeterminate, delivered; latencies are p50, p99. */
  private Series(
      int seconds, long[][] counts, double[][] latencies, List<Window> faults, long originNanos) {
    this.seconds = seconds;
    this.confirmed = counts[0];
    this.failed = counts[1];
    this.indeterminate = counts[2];
    this.delivered = counts[3];
    this.latencyP50Millis = latencies[0];
    this.latencyP99Millis = latencies[1];
    this.faults = List.copyOf(faults);
    this.originNanos = originNanos;
  }

  /**
   * Returns the number of one-second buckets.
   *
   * @return the length of every series
   */
  public int seconds() {
    return seconds;
  }

  /**
   * Returns the confirmed publishes per second.
   *
   * @return a copy of the series
   */
  public long[] confirmed() {
    return confirmed.clone();
  }

  /**
   * Returns the negatively confirmed publishes per second.
   *
   * @return a copy of the series
   */
  public long[] failed() {
    return failed.clone();
  }

  /**
   * Returns the indeterminate publishes per second.
   *
   * @return a copy of the series
   */
  public long[] indeterminate() {
    return indeterminate.clone();
  }

  /**
   * Returns the deliveries per second.
   *
   * @return a copy of the series
   */
  public long[] delivered() {
    return delivered.clone();
  }

  /**
   * Returns the median publish-to-confirm latency of the publishes confirmed in each second.
   *
   * @return a copy of the series, in milliseconds
   */
  public double[] latencyP50Millis() {
    return latencyP50Millis.clone();
  }

  /**
   * Returns the 99th percentile of the same latencies.
   *
   * @return a copy of the series, in milliseconds
   */
  public double[] latencyP99Millis() {
    return latencyP99Millis.clone();
  }

  /**
   * Returns the fault windows.
   *
   * @return windows in start order
   */
  public List<Window> faults() {
    return faults;
  }

  /**
   * Computes the series of a history.
   *
   * @param history the history
   * @return the series; empty arrays for an empty history
   */
  public static Series of(History history) {
    List<Operation> ops = history.operations();
    if (ops.isEmpty()) {
      return new Series(0, new long[4][0], new double[2][0], List.of(), 0);
    }
    long origin = ops.get(0).timestampNanos();
    int seconds = second(origin, ops.get(ops.size() - 1).timestampNanos()) + 1;
    long[] ok = new long[seconds];
    long[] fail = new long[seconds];
    long[] info = new long[seconds];
    long[] delivered = new long[seconds];
    List<List<Double>> latencies = new ArrayList<>();
    for (int i = 0; i < seconds; i++) {
      latencies.add(new ArrayList<>());
    }
    Map<Integer, Operation> openFaults = new HashMap<>();
    List<Window> windows = new ArrayList<>();
    for (Operation op : ops) {
      int s = second(origin, op.timestampNanos());
      switch (op.function()) {
        case CONFIRM -> {
          (op.type() == OpType.OK ? ok : op.type() == OpType.FAIL ? fail : info)[s]++;
          if (op.type() == OpType.OK) {
            history
                .invocationOf(op)
                .ifPresent(
                    inv ->
                        latencies.get(s).add((op.timestampNanos() - inv.timestampNanos()) / 1e6));
          }
        }
        case DELIVER -> delivered[s]++;
        case NEMESIS_START -> openFaults.put(((Value.Fault) op.value()).index(), op);
        case NEMESIS_STOP -> {
          Operation start = openFaults.remove(((Value.Fault) op.value()).index());
          if (start != null) {
            windows.add(window(start, op.timestampNanos(), origin));
          }
        }
        default -> {
          // Other functions are not charted.
        }
      }
    }
    long end = ops.get(ops.size() - 1).timestampNanos();
    openFaults.values().forEach(start -> windows.add(window(start, end, origin)));
    windows.sort((a, b) -> Double.compare(a.startSeconds(), b.startSeconds()));
    double[] p50 = new double[seconds];
    double[] p99 = new double[seconds];
    for (int i = 0; i < seconds; i++) {
      p50[i] = percentile(latencies.get(i), 0.50);
      p99[i] = percentile(latencies.get(i), 0.99);
    }
    return new Series(
        seconds,
        new long[][] {ok, fail, info, delivered},
        new double[][] {p50, p99},
        windows,
        origin);
  }

  /**
   * Converts a monotonic timestamp into seconds from the origin.
   *
   * @param nanos monotonic time
   * @return offset in seconds
   */
  public double offsetSeconds(long nanos) {
    return (nanos - originNanos) / 1e9;
  }

  /**
   * Returns the total number of confirmed publishes.
   *
   * @return the sum of {@link #confirmed()}
   */
  public long totalConfirmed() {
    return Arrays.stream(confirmed).sum();
  }

  private static Window window(Operation start, long endNanos, long origin) {
    Value.Fault fault = (Value.Fault) start.value();
    return new Window(
        fault.index(),
        fault.nemesis(),
        (start.timestampNanos() - origin) / 1e9,
        (endNanos - origin) / 1e9);
  }

  private static int second(long origin, long nanos) {
    return (int) ((nanos - origin) / 1_000_000_000L);
  }

  /** Returns NaN when there is no sample: no confirm in that second is not a zero latency. */
  static double percentile(List<Double> values, double quantile) {
    if (values.isEmpty()) {
      return Double.NaN;
    }
    double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    int index = (int) Math.ceil(quantile * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
  }

  @Override
  public String toString() {
    return "Series[seconds="
        + seconds
        + ", confirmed="
        + totalConfirmed()
        + ", faults="
        + faults.size()
        + "]";
  }
}
