package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.model.Verdict;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Messages delivered more than once (METHODOLOGY 4.2). For destructive queues a repeated delivery
 * is legitimate only when flagged {@code redelivered}. For streams, the offset tells a replay of
 * the same log entry (legitimate) from a second copy of the message in the log (violation).
 */
public final class DuplicateDeliveriesChecker implements Checker {

  /** Name of this checker. */
  public static final String NAME = "DuplicateDeliveries";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Verdict check(History history, CheckerContext context) {
    MessageLedger ledger = MessageLedger.of(history);
    Findings findings = new Findings(NAME);
    long duplicated = 0;
    for (MessageRecord record : ledger.messages()) {
      List<Operation> deliveries = record.allDeliveries();
      if (deliveries.size() < 2) {
        continue;
      }
      boolean reported =
          ledger.isLog(record.message().queue())
              ? judgeLog(record, deliveries, findings)
              : judgeDestructive(record, deliveries, findings);
      duplicated += reported ? 1 : 0;
    }
    findings.metric("deliveries", ledger.deliveries().size());
    findings.metric("duplicatedMessages", duplicated);
    findings.metric(
        "legitimateRedeliveries", findings.count("REDELIVERY") + findings.count("OFFSET_REPLAY"));
    findings.metric(
        "unexpectedDuplicates",
        findings.count("DUPLICATE_UNEXPECTED") + findings.count("DUPLICATE_IN_LOG"));
    return findings.verdict(
        !ledger.deliveries().isEmpty(),
        duplicated + " message(s) delivered more than once out of " + ledger.messages().size());
  }

  private static boolean judgeDestructive(
      MessageRecord record, List<Operation> deliveries, Findings findings) {
    List<Operation> unflagged = new ArrayList<>();
    for (Operation repeat : deliveries.subList(1, deliveries.size())) {
      if (!((Value.Delivery) repeat.value()).redelivered()) {
        unflagged.add(repeat);
      }
    }
    String what =
        LostMessagesChecker.describe(record) + " delivered " + deliveries.size() + " times";
    if (unflagged.isEmpty()) {
      findings.note("REDELIVERY", what + ", every repeat flagged redelivered", deliveries);
    } else {
      findings.violation(
          "DUPLICATE_UNEXPECTED",
          what + ", " + unflagged.size() + " repeat(s) without the redelivered flag",
          deliveries);
    }
    return true;
  }

  /**
   * Every reader of a stream sees every entry, so the same offset read by several processes (a
   * consumer and the drain) is expected and not reported.
   */
  private static boolean judgeLog(
      MessageRecord record, List<Operation> deliveries, Findings findings) {
    Set<Long> offsets = new HashSet<>();
    Set<String> readers = new HashSet<>();
    boolean replayed = false;
    for (Operation delivery : deliveries) {
      offsets.add(((Value.Delivery) delivery.value()).deliveryTag());
      replayed |= !readers.add(delivery.process());
    }
    String what = LostMessagesChecker.describe(record) + " read " + deliveries.size() + " times";
    if (offsets.size() > 1) {
      findings.violation(
          "DUPLICATE_IN_LOG", what + " at " + offsets.size() + " distinct offsets", deliveries);
      return true;
    }
    if (replayed) {
      findings.note(
          "OFFSET_REPLAY", what + ", replayed by the same reader at one offset", deliveries);
    }
    return replayed;
  }
}
