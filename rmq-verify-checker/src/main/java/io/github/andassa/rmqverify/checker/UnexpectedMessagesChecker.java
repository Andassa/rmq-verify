package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.model.Verdict;
import java.util.List;

/**
 * Deliveries that do not correspond to a publication of this run: undecodable payloads, unknown
 * identifiers and payloads whose fields disagree with the publication (METHODOLOGY 4.3).
 */
public final class UnexpectedMessagesChecker implements Checker {

  /** Name of this checker. */
  public static final String NAME = "UnexpectedMessages";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Verdict check(History history, CheckerContext context) {
    MessageLedger ledger = MessageLedger.of(history);
    Findings findings = new Findings(NAME);
    String runId = history.header().runId();
    for (Operation corrupt : ledger.corruptDeliveries()) {
      Value.CorruptDelivery value = (Value.CorruptDelivery) corrupt.value();
      findings.violation(
          "UNEXPECTED_CORRUPT",
          "undecodable payload " + value.digest() + " from " + value.queue(),
          List.of(corrupt));
    }
    for (Operation unknown : ledger.unknownDeliveries()) {
      Message msg = ((Value.Delivery) unknown.value()).msg();
      if (msg.runId().equals(runId)) {
        findings.violation(
            "UNEXPECTED_UNKNOWN",
            "message " + msg.uuid() + " was never published",
            List.of(unknown));
      } else {
        findings.note(
            "FOREIGN_RUN",
            "residue of run " + msg.runId() + " in " + msg.queue(),
            List.of(unknown));
      }
    }
    for (MessageRecord record : ledger.messages()) {
      for (Operation delivery : record.allDeliveries()) {
        Message received = ((Value.Delivery) delivery.value()).msg();
        if (!received.equals(record.message())) {
          findings.violation(
              "UNEXPECTED_MISMATCH",
              "delivered " + received + " differs from published " + record.message(),
              List.of(record.publish(), delivery));
        }
      }
      if (record.outcome() == OpType.FAIL && record.delivered()) {
        findings.note(
            "FAILED_BUT_DELIVERED",
            LostMessagesChecker.describe(record) + " was negatively confirmed but delivered",
            List.of(record.confirm().orElseThrow(), record.allDeliveries().get(0)));
      }
    }
    findings.metric("deliveries", ledger.deliveries().size());
    findings.metric("corrupt", findings.count("UNEXPECTED_CORRUPT"));
    findings.metric("unknown", findings.count("UNEXPECTED_UNKNOWN"));
    findings.metric("mismatched", findings.count("UNEXPECTED_MISMATCH"));
    findings.metric("foreign", findings.count("FOREIGN_RUN"));
    findings.metric("failedButDelivered", findings.count("FAILED_BUT_DELIVERED"));
    long unexpected =
        findings.count("UNEXPECTED_CORRUPT")
            + findings.count("UNEXPECTED_UNKNOWN")
            + findings.count("UNEXPECTED_MISMATCH");
    return findings.verdict(
        !ledger.deliveries().isEmpty(),
        unexpected + " unexpected of " + ledger.deliveries().size() + " deliveries");
  }
}
