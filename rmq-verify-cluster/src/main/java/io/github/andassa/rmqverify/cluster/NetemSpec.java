package io.github.andassa.rmqverify.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parameters of a {@code tc netem} queueing discipline.
 *
 * @param delayMillis added one-way delay
 * @param jitterMillis random variation of the delay
 * @param lossPercent probability of dropping a packet, between 0 and 100
 */
public record NetemSpec(int delayMillis, int jitterMillis, double lossPercent) {

  /** Validates the ranges. */
  public NetemSpec {
    if (delayMillis < 0 || delayMillis > 60_000 || jitterMillis < 0 || jitterMillis > delayMillis) {
      throw new IllegalArgumentException(
          "invalid delay/jitter: " + delayMillis + "/" + jitterMillis);
    }
    if (lossPercent < 0 || lossPercent > 100 || Double.isNaN(lossPercent)) {
      throw new IllegalArgumentException("invalid loss: " + lossPercent);
    }
    if (delayMillis == 0 && lossPercent == 0) {
      throw new IllegalArgumentException("netem without delay nor loss has no effect");
    }
  }

  /**
   * Renders the {@code netem} arguments.
   *
   * @return arguments following {@code tc qdisc replace dev eth0 root netem}
   */
  public List<String> arguments() {
    List<String> args = new ArrayList<>();
    if (delayMillis > 0) {
      args.addAll(List.of("delay", delayMillis + "ms", jitterMillis + "ms"));
    }
    if (lossPercent > 0) {
      args.addAll(List.of("loss", String.format(Locale.ROOT, "%.2f%%", lossPercent)));
    }
    return args;
  }
}
