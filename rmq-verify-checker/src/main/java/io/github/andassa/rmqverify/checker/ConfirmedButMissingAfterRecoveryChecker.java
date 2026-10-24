package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Messages confirmed before a fault and absent once the fault is healed (METHODOLOGY 4.5). This
 * separates the losses a fault caused from losses that happen in steady state.
 */
public final class ConfirmedButMissingAfterRecoveryChecker implements Checker {

  /** Name of this checker. */
  public static final String NAME = "ConfirmedButMissingAfterRecovery";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Verdict check(History history, CheckerContext context) {
    MessageLedger ledger = MessageLedger.of(history);
    Findings findings = new Findings(NAME);
    List<FaultWindow> faults = ledger.faults();
    long exposed = 0;
    long unverifiable = 0;
    for (MessageRecord record : ledger.messages()) {
      if (record.outcome() != OpType.OK) {
        continue;
      }
      long confirmedAt = record.confirm().orElseThrow().timestampNanos();
      Optional<FaultWindow> next =
          faults.stream().filter(w -> w.start().timestampNanos() > confirmedAt).findFirst();
      if (next.isEmpty()) {
        continue;
      }
      FaultWindow window = next.get();
      boolean log = ledger.isLog(record.message().queue());
      if (!window.closed() || !ledger.drained(record.message().queue())) {
        unverifiable++;
        continue;
      }
      long healedAt = window.stop().orElseThrow().timestampNanos();
      if (!log && ackedBefore(record, healedAt)) {
        continue;
      }
      exposed++;
      boolean seenAfter =
          record.allDeliveries().stream().anyMatch(d -> d.timestampNanos() > healedAt);
      if (!seenAfter) {
        List<Operation> evidence =
            new ArrayList<>(List.of(record.publish(), record.confirm().orElseThrow()));
        evidence.add(window.start());
        evidence.add(window.stop().orElseThrow());
        findings.violation(
            "MISSING_AFTER_RECOVERY",
            LostMessagesChecker.describe(record)
                + " confirmed before fault #"
                + window.index()
                + " ("
                + window.nemesis()
                + ") and absent after its heal",
            evidence);
      }
    }
    if (unverifiable > 0) {
      findings.note(
          "RECOVERY_UNVERIFIABLE",
          unverifiable
              + " message(s) confirmed before a fault that was not healed or a queue that was not drained",
          List.of());
    }
    findings.metric("faults", faults.size());
    findings.metric("exposedMessages", exposed);
    findings.metric("missingAfterRecovery", findings.count("MISSING_AFTER_RECOVERY"));
    findings.metric("unverifiable", unverifiable);
    return findings.verdict(
        ledger.hasPublications() && unverifiable == 0,
        findings.count("MISSING_AFTER_RECOVERY")
            + " of "
            + exposed
            + " messages exposed to "
            + faults.size()
            + " fault(s) missing after recovery");
  }

  private static boolean ackedBefore(MessageRecord record, long nanos) {
    return record.acks().stream()
        .anyMatch(a -> a.type() == OpType.OK && a.timestampNanos() < nanos);
  }
}
