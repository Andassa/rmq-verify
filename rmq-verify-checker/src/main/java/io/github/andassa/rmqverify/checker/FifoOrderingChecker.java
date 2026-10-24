package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.model.Verdict;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-publisher FIFO (METHODOLOGY 4.4): inversions among first-time deliveries to one consumer, and
 * gaps where a confirmed message is missing while a successor of the same publisher survived.
 */
public final class FifoOrderingChecker implements Checker {

  /** Name of this checker. */
  public static final String NAME = "FifoOrdering";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Verdict check(History history, CheckerContext context) {
    MessageLedger ledger = MessageLedger.of(history);
    Findings findings = new Findings(NAME);
    long judged = findInversions(ledger, findings);
    long gapsSkipped = findGaps(ledger, findings);
    findings.metric("orderedDeliveries", judged);
    findings.metric("inversions", findings.count("FIFO_INVERSION"));
    findings.metric("gaps", findings.count("FIFO_GAP"));
    findings.metric("gapChecksSkippedUndrained", gapsSkipped);
    return findings.verdict(
        !ledger.deliveries().isEmpty(),
        findings.count("FIFO_INVERSION")
            + " inversion(s), "
            + findings.count("FIFO_GAP")
            + " gap(s) over "
            + judged
            + " first-time deliveries");
  }

  private static long findInversions(MessageLedger ledger, Findings findings) {
    Map<String, Operation> lastByStream = new HashMap<>();
    Map<String, Set<String>> seenByProcess = new HashMap<>();
    long judged = 0;
    for (Operation op : ledger.deliveries()) {
      if (!(op.value() instanceof Value.Delivery delivery) || delivery.redelivered()) {
        continue;
      }
      MessageRecord record = ledger.record(delivery.msg().uuid()).orElse(null);
      boolean firstForProcess =
          seenByProcess
              .computeIfAbsent(op.process(), p -> new HashSet<>())
              .add(delivery.msg().uuid());
      if (record == null || !firstForProcess) {
        continue;
      }
      judged++;
      Message msg = record.message();
      String stream = op.process() + '\u0000' + msg.publisherId() + '\u0000' + msg.queue();
      Operation previous = lastByStream.put(stream, op);
      if (previous != null) {
        Message before =
            ledger.record(((Value.Delivery) previous.value()).msg().uuid()).orElseThrow().message();
        if (before.sequence() > msg.sequence()) {
          findings.violation(
              "FIFO_INVERSION",
              op.process()
                  + " received "
                  + msg.publisherId()
                  + "#"
                  + msg.sequence()
                  + " after #"
                  + before.sequence()
                  + " in "
                  + msg.queue(),
              List.of(previous, op));
        }
      }
    }
    return judged;
  }

  /** Returns the number of confirmed messages whose gap check was skipped (queue not drained). */
  private static long findGaps(MessageLedger ledger, Findings findings) {
    Map<String, NavigableMap<Long, MessageRecord>> byStream = new HashMap<>();
    for (MessageRecord record : ledger.messages()) {
      Message msg = record.message();
      byStream
          .computeIfAbsent(msg.publisherId() + '\u0000' + msg.queue(), k -> new TreeMap<>())
          .put(msg.sequence(), record);
    }
    long skipped = 0;
    for (NavigableMap<Long, MessageRecord> stream : byStream.values()) {
      MessageRecord lastDelivered = null;
      for (MessageRecord record : stream.descendingMap().values()) {
        if (record.delivered()) {
          lastDelivered = record;
          continue;
        }
        if (record.outcome() != OpType.OK || lastDelivered == null) {
          continue;
        }
        if (!ledger.drained(record.message().queue())) {
          skipped++;
          continue;
        }
        findings.violation(
            "FIFO_GAP",
            "confirmed "
                + LostMessagesChecker.describe(record)
                + " missing while #"
                + lastDelivered.message().sequence()
                + " was delivered",
            List.of(
                record.publish(),
                record.confirm().orElseThrow(),
                lastDelivered.allDeliveries().get(0)));
      }
    }
    return skipped;
  }
}
