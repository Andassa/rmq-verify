package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.Verdict;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Success rate per window, unavailability periods and recovery time after each heal (METHODOLOGY
 * 4.6). Availability is not a RabbitMQ guarantee: the bounds come from the context.
 */
public final class AvailabilityChecker implements Checker {

  /** Name of this checker. */
  public static final String NAME = "Availability";

  private static final double NANOS_PER_MILLI = 1_000_000.0;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Verdict check(History history, CheckerContext context) {
    MessageLedger ledger = MessageLedger.of(history);
    Findings findings = new Findings(NAME);
    List<MessageRecord> publications = new ArrayList<>(ledger.messages());
    if (publications.isEmpty()) {
      return findings.verdict(false, "no publication");
    }
    long confirmed = publications.stream().filter(r -> r.outcome() == OpType.OK).count();
    findings.metric("successRate", (double) confirmed / publications.size());
    measureUnavailability(publications, context.availabilityWindow(), findings);
    measureRecoveries(ledger, publications, context.maxRecovery(), findings);
    return findings.verdict(
        true,
        String.format(
            java.util.Locale.ROOT,
            "%.1f%% of publishes confirmed, longest unavailability %.0f ms",
            100.0 * confirmed / publications.size(),
            findings.metricValue("maxUnavailabilityMs")));
  }

  /**
   * A window is unavailable when no publish was confirmed in it. Publishers are synchronous, so a
   * blocked publisher stops invoking: counting invocations would hide exactly the outages we
   * measure.
   */
  private static void measureUnavailability(
      List<MessageRecord> publications, Duration window, Findings findings) {
    long width = window.toNanos();
    long origin = publications.get(0).publish().timestampNanos();
    long last = publications.get(publications.size() - 1).publish().timestampNanos();
    int buckets = (int) ((last - origin) / width) + 1;
    boolean[] confirmed = new boolean[buckets];
    for (MessageRecord record : publications) {
      if (record.outcome() == OpType.OK) {
        long at = record.confirm().orElseThrow().timestampNanos();
        confirmed[(int) Math.min(buckets - 1, (at - origin) / width)] = true;
      }
    }
    long longest = 0;
    long total = 0;
    long current = 0;
    for (boolean ok : confirmed) {
      current = ok ? 0 : current + 1;
      total += ok ? 0 : 1;
      longest = Math.max(longest, current);
    }
    findings.metric("windows", buckets);
    findings.metric("unavailableWindows", total);
    findings.metric("maxUnavailabilityMs", (double) longest * width / NANOS_PER_MILLI);
    findings.metric("totalUnavailabilityMs", (double) total * width / NANOS_PER_MILLI);
  }

  private static void measureRecoveries(
      MessageLedger ledger,
      List<MessageRecord> publications,
      Duration maxRecovery,
      Findings findings) {
    double maxMs = 0;
    double sumMs = 0;
    int measured = 0;
    for (FaultWindow window : ledger.faults()) {
      if (!window.closed()) {
        continue;
      }
      Operation heal = window.stop().orElseThrow();
      long healedAt = heal.timestampNanos();
      boolean attempted =
          publications.stream().anyMatch(r -> r.publish().timestampNanos() >= healedAt);
      Optional<Operation> firstSuccess =
          publications.stream()
              .filter(r -> r.publish().timestampNanos() >= healedAt && r.outcome() == OpType.OK)
              .map(r -> r.confirm().orElseThrow())
              .min((a, b) -> Long.compare(a.timestampNanos(), b.timestampNanos()));
      String fault = "fault #" + window.index() + " (" + window.nemesis() + ")";
      if (firstSuccess.isEmpty()) {
        if (attempted) {
          findings.violation(
              "NOT_RECOVERED", fault + ": no publish succeeded after the heal", List.of(heal));
        } else {
          findings.note(
              "RECOVERY_UNOBSERVED",
              fault + ": no publish attempted after the heal",
              List.of(heal));
        }
        continue;
      }
      double recoveryMs = (firstSuccess.get().timestampNanos() - healedAt) / NANOS_PER_MILLI;
      measured++;
      sumMs += recoveryMs;
      maxMs = Math.max(maxMs, recoveryMs);
      findings.metric("recoveryMs." + window.index(), recoveryMs);
      if (recoveryMs > maxRecovery.toNanos() / NANOS_PER_MILLI) {
        findings.violation(
            "RECOVERY_SLOW",
            String.format(
                java.util.Locale.ROOT,
                "%s: recovered in %.0f ms, bound is %d ms",
                fault,
                recoveryMs,
                maxRecovery.toMillis()),
            List.of(heal, firstSuccess.get()));
      }
    }
    findings.metric("recoveriesMeasured", measured);
    findings.metric("maxRecoveryMs", maxMs);
    findings.metric("meanRecoveryMs", measured == 0 ? 0 : sumMs / measured);
  }
}
