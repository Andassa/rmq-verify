package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Confirmed messages that were never delivered, not even by the final drain (METHODOLOGY 4.1).
 * Indeterminate messages are counted, never reported as lost.
 */
public final class LostMessagesChecker implements Checker {

  /** Name of this checker. */
  public static final String NAME = "LostMessages";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Verdict check(History history, CheckerContext context) {
    MessageLedger ledger = MessageLedger.of(history);
    Findings findings = new Findings(NAME);
    long confirmed = 0;
    long failed = 0;
    long indeterminate = 0;
    long indeterminateSurvived = 0;
    Map<String, List<Operation>> unverifiable = new TreeMap<>();
    for (MessageRecord record : ledger.messages()) {
      OpType outcome = record.outcome();
      if (outcome == OpType.FAIL) {
        failed++;
      } else if (outcome == OpType.INFO) {
        indeterminate++;
        indeterminateSurvived += record.delivered() ? 1 : 0;
      } else {
        confirmed++;
        if (!record.delivered()) {
          String queue = record.message().queue();
          if (ledger.drained(queue)) {
            findings.violation(
                "LOST_CONFIRMED",
                "confirmed message " + describe(record) + " was never delivered, queue was drained",
                evidence(record, ledger.drainCompletion(queue).orElseThrow()));
          } else {
            unverifiable
                .computeIfAbsent(queue, q -> new ArrayList<>())
                .add(record.confirm().orElseThrow());
          }
        }
      }
    }
    unverifiable.forEach(
        (queue, confirms) ->
            findings.note(
                "LOST_UNVERIFIABLE",
                confirms.size()
                    + " confirmed message(s) of "
                    + queue
                    + " undelivered but the queue was not drained",
                confirms));
    long unverifiableCount = unverifiable.values().stream().mapToLong(List::size).sum();
    long lost = findings.count("LOST_CONFIRMED");
    findings.metric("published", ledger.messages().size());
    findings.metric("confirmed", confirmed);
    findings.metric("failed", failed);
    findings.metric("indeterminate", indeterminate);
    findings.metric("indeterminateSurvived", indeterminateSurvived);
    findings.metric("indeterminateNotDelivered", indeterminate - indeterminateSurvived);
    findings.metric("lostConfirmed", lost);
    findings.metric("unverifiable", unverifiableCount);
    boolean conclusive = ledger.hasPublications() && unverifiableCount == 0;
    return findings.verdict(
        conclusive,
        lost
            + " lost of "
            + confirmed
            + " confirmed; "
            + indeterminate
            + " indeterminate ("
            + indeterminateSurvived
            + " survived)");
  }

  static String describe(MessageRecord record) {
    return record.message().publisherId()
        + "#"
        + record.message().sequence()
        + " in "
        + record.message().queue();
  }

  private static List<Operation> evidence(MessageRecord record, Operation drainCompletion) {
    List<Operation> evidence = new ArrayList<>();
    evidence.add(record.publish());
    record.confirm().ifPresent(evidence::add);
    evidence.add(drainCompletion);
    return evidence;
  }
}
