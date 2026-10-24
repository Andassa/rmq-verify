package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.model.Verdict;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Divergent cluster views reported by the nodes themselves (METHODOLOGY 4.7). Divergence is
 * expected while a fault is active; it is a violation once every fault is healed and settled.
 */
public final class SplitBrainIndicatorsChecker implements Checker {

  /** Name of this checker. */
  public static final String NAME = "SplitBrainIndicators";

  private static final String CLUSTER_SUBJECT = "cluster";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Verdict check(History history, CheckerContext context) {
    MessageLedger ledger = MessageLedger.of(history);
    Findings findings = new Findings(NAME);
    Map<Long, List<Operation>> rounds = new TreeMap<>();
    for (Operation op : history.operations()) {
      if (op.function() == OpFunction.PROBE) {
        rounds.computeIfAbsent(((Value.Probe) op.value()).round(), r -> new ArrayList<>()).add(op);
      }
    }
    long settle = context.settle().toNanos();
    long end = ledger.endNanos();
    long quietRounds = 0;
    for (List<Operation> probes : rounds.values()) {
      long at = probes.get(0).timestampNanos();
      boolean quiet = ledger.faults().stream().noneMatch(w -> w.covers(at, settle, end));
      quietRounds += quiet ? 1 : 0;
      judgeRound(probes, quiet, findings);
    }
    findings.metric("probeRounds", rounds.size());
    findings.metric("quietRounds", quietRounds);
    findings.metric("leaderConflicts", findings.count("LEADER_CONFLICT"));
    findings.metric("viewDivergences", findings.count("VIEW_DIVERGENCE"));
    findings.metric("partitionReports", findings.count("PARTITION_REPORTED"));
    return findings.verdict(
        !rounds.isEmpty(),
        rounds.size() + " probe round(s), " + quietRounds + " outside fault windows");
  }

  private static void judgeRound(List<Operation> probes, boolean quiet, Findings findings) {
    Map<String, Map<String, Operation>> leadersBySubject = new LinkedHashMap<>();
    Map<Set<String>, Operation> views = new LinkedHashMap<>();
    Set<String> partitioned = new HashSet<>();
    List<Operation> partitionEvidence = new ArrayList<>();
    for (Operation op : probes) {
      Value.Probe probe = (Value.Probe) op.value();
      if (!probe.subject().equals(CLUSTER_SUBJECT) && !probe.leader().isEmpty()) {
        leadersBySubject
            .computeIfAbsent(probe.subject(), s -> new LinkedHashMap<>())
            .putIfAbsent(probe.leader(), op);
      }
      views.putIfAbsent(new TreeSet<>(probe.runningNodes()), op);
      if (!probe.partitions().isEmpty()) {
        partitioned.add(probe.observer());
        partitionEvidence.add(op);
      }
    }
    long round = ((Value.Probe) probes.get(0).value()).round();
    String when = quiet ? " after every fault was healed" : " during a fault";
    leadersBySubject.forEach(
        (subject, leaders) -> {
          if (leaders.size() > 1) {
            report(
                findings,
                quiet,
                "LEADER_CONFLICT",
                "round " + round + ": " + subject + " has leaders " + leaders.keySet() + when,
                leaders.values());
          }
        });
    if (views.size() > 1) {
      report(
          findings,
          quiet,
          "VIEW_DIVERGENCE",
          "round " + round + ": " + views.size() + " different running-node sets" + when,
          views.values());
    }
    if (!partitioned.isEmpty()) {
      report(
          findings,
          quiet,
          "PARTITION_REPORTED",
          "round " + round + ": " + new TreeSet<>(partitioned) + " report partitions" + when,
          partitionEvidence);
    }
  }

  private static void report(
      Findings findings,
      boolean violation,
      String kind,
      String description,
      java.util.Collection<Operation> evidence) {
    if (violation) {
      findings.violation(kind, description, evidence);
    } else {
      findings.note(kind, description, evidence);
    }
  }
}
