package io.github.andassa.rmqverify.checker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.andassa.rmqverify.model.Anomaly;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpFunction;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.Value;
import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.VerdictStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class CheckerProperties {

  private static final CheckerContext CONTEXT = CheckerContext.defaults();
  private static final Checkers ALL = Checkers.load();

  @Provide
  Arbitrary<SyntheticRun> runs() {
    return Combinators.combine(
            Arbitraries.longs(),
            Arbitraries.of(QueueKind.class),
            Arbitraries.integers().between(1, 3),
            Arbitraries.integers().between(1, 25),
            Arbitraries.integers().between(1, 3))
        .as(SyntheticRun::generate);
  }

  @Property(tries = 300)
  void correctRunsAreValidForEveryChecker(@ForAll("runs") SyntheticRun run) {
    History history = run.render();
    List<Verdict> verdicts = ALL.checkAll(history, CONTEXT);
    assertThat(verdicts).hasSize(7);
    for (Verdict verdict : verdicts) {
      assertThat(verdict.status())
          .as(verdict.checker() + ": " + verdict.anomalies())
          .isEqualTo(VerdictStatus.VALID);
    }
  }

  @Property(tries = 200)
  void removingTheDeliveriesOfAConfirmedMessageIsALoss(
      @ForAll("runs") SyntheticRun run, @ForAll long pick) {
    List<Message> candidates = confirmedMessages(run);
    Message lost = candidates.get(Math.floorMod(pick, candidates.size()));
    History history = run.with(without(run.events, ev -> concerns(ev, lost))).render();
    Verdict verdict = new LostMessagesChecker().check(history, CONTEXT);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.INVALID);
    assertThat(evidenceOf(verdict, "LOST_CONFIRMED")).contains(publishOf(history, lost).id());
  }

  @Property(tries = 200)
  void anUnflaggedSecondDeliveryIsADuplicate(@ForAll("runs") SyntheticRun run, @ForAll long pick) {
    List<Integer> deliveries = indicesOf(run.events, ev -> ev instanceof Ev.Del);
    int index = deliveries.get(Math.floorMod(pick, deliveries.size()));
    Ev.Del original = (Ev.Del) run.events.get(index);
    List<Ev> events = new ArrayList<>(run.events);
    events.add(events.size() - 1, new Ev.Del(original.consumer(), original.m(), false, 1_000_000));
    History history = run.with(events).render();
    Verdict verdict = new DuplicateDeliveriesChecker().check(history, CONTEXT);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.INVALID);
    String kind = run.kind.isLog() ? "DUPLICATE_IN_LOG" : "DUPLICATE_UNEXPECTED";
    Operation injected = deliveriesOf(history, original.m()).getLast();
    assertThat(evidenceOf(verdict, kind)).contains(injected.id());
  }

  @Property(tries = 200)
  void aDeliveryOfAnUnpublishedMessageIsUnexpected(
      @ForAll("runs") SyntheticRun run, @ForAll long pick) {
    Message ghost = new Message("run-1", "ghost", SyntheticRun.QUEUE, 0, "ghost-uuid");
    List<Ev> events = new ArrayList<>(run.events);
    events.add(Math.floorMod(pick, events.size()), new Ev.Del("c0", ghost, false, 2_000_000));
    History history = run.with(events).render();
    Verdict verdict = new UnexpectedMessagesChecker().check(history, CONTEXT);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.INVALID);
    assertThat(evidenceOf(verdict, "UNEXPECTED_UNKNOWN"))
        .containsExactly(deliveriesOf(history, ghost).get(0).id());
  }

  @Property(tries = 200)
  void swappingTwoDeliveriesOfAPublisherIsAnInversion(
      @ForAll("runs") SyntheticRun run, @ForAll long pick) {
    List<int[]> pairs = orderedPairs(run.events);
    Assume.that(!pairs.isEmpty());
    int[] pair = pairs.get(Math.floorMod(pick, pairs.size()));
    List<Ev> events = new ArrayList<>(run.events);
    Ev first = events.get(pair[0]);
    events.set(pair[0], events.get(pair[1]));
    events.set(pair[1], first);
    History history = run.with(events).render();
    Verdict verdict = new FifoOrderingChecker().check(history, CONTEXT);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.INVALID);
    Message early = ((Ev.Del) events.get(pair[0])).m();
    Message late = ((Ev.Del) events.get(pair[1])).m();
    assertThat(evidenceOf(verdict, "FIFO_INVERSION"))
        .contains(
            deliveryBy(history, ((Ev.Del) first).consumer(), early).id(),
            deliveryBy(history, ((Ev.Del) first).consumer(), late).id());
  }

  @Property(tries = 300, maxDiscardRatio = 20)
  void deliveriesMissingAfterAHealAreDetected(@ForAll("runs") SyntheticRun run, @ForAll long pick) {
    List<Message> candidates = exposedMessages(run);
    Assume.that(!candidates.isEmpty());
    Message victim = candidates.get(Math.floorMod(pick, candidates.size()));
    int heal = healAfter(run.events, publicationIndex(run.events, victim));
    List<Ev> events = new ArrayList<>();
    for (int i = 0; i < run.events.size(); i++) {
      Ev ev = run.events.get(i);
      if (i < heal || !concerns(ev, victim)) {
        events.add(ev);
      }
    }
    History history = run.with(events).render();
    Verdict verdict = new ConfirmedButMissingAfterRecoveryChecker().check(history, CONTEXT);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.INVALID);
    assertThat(evidenceOf(verdict, "MISSING_AFTER_RECOVERY"))
        .contains(publishOf(history, victim).id());
  }

  @Property(tries = 200, maxDiscardRatio = 20)
  void aLongPauseAfterAHealIsASlowRecovery(@ForAll("runs") SyntheticRun run, @ForAll long pick) {
    List<Integer> heals = new ArrayList<>();
    for (int i = 0; i < run.events.size(); i++) {
      if (run.events.get(i) instanceof Ev.FaultStop && hasPublicationAfter(run.events, i)) {
        heals.add(i);
      }
    }
    Assume.that(!heals.isEmpty());
    int heal = heals.get(Math.floorMod(pick, heals.size()));
    List<Ev> events = new ArrayList<>(run.events);
    events.add(heal + 1, new Ev.Advance(Duration.ofSeconds(61)));
    History history = run.with(events).render();
    Verdict verdict = new AvailabilityChecker().check(history, CONTEXT);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.INVALID);
    int index = ((Ev.FaultStop) run.events.get(heal)).index();
    Operation stop =
        history.operations().stream()
            .filter(
                op ->
                    op.function() == OpFunction.NEMESIS_STOP
                        && ((Value.Fault) op.value()).index() == index)
            .findFirst()
            .orElseThrow();
    assertThat(evidenceOf(verdict, "RECOVERY_SLOW")).contains(stop.id());
  }

  @Property(tries = 100)
  void twoLeadersAfterTheHealAreASplitBrain(@ForAll("runs") SyntheticRun run) {
    List<Ev> events = new ArrayList<>(run.events);
    int last =
        indicesOf(events, ev -> ev instanceof Ev.Probe p && p.observer().equals("rabbit2"))
            .getLast();
    Ev.Probe probe = (Ev.Probe) events.get(last);
    events.set(
        last,
        new Ev.Probe(probe.round(), "rabbit2", "rabbit2", probe.running(), probe.partitions()));
    History history = run.with(events).render();
    Verdict verdict = new SplitBrainIndicatorsChecker().check(history, CONTEXT);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.INVALID);
    assertThat(verdict.anomalies())
        .filteredOn(Anomaly::isViolation)
        .extracting(Anomaly::kind)
        .containsExactly("LEADER_CONFLICT");
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static boolean concerns(Ev ev, Message m) {
    return (ev instanceof Ev.Del d && d.m().equals(m))
        || (ev instanceof Ev.Ack a && a.m().equals(m));
  }

  private static List<Ev> without(List<Ev> events, Predicate<Ev> drop) {
    return events.stream().filter(drop.negate()).toList();
  }

  private static List<Integer> indicesOf(List<Ev> events, Predicate<Ev> keep) {
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < events.size(); i++) {
      if (keep.test(events.get(i))) {
        indices.add(i);
      }
    }
    return indices;
  }

  private static List<Message> confirmedMessages(SyntheticRun run) {
    return run.events.stream()
        .filter(ev -> ev instanceof Ev.Pub p && p.outcome() == OpType.OK)
        .map(ev -> ((Ev.Pub) ev).m())
        .toList();
  }

  /**
   * Confirmed messages not acknowledged before the heal of the first fault after their publication.
   */
  private static List<Message> exposedMessages(SyntheticRun run) {
    List<Message> exposed = new ArrayList<>();
    for (Message m : confirmedMessages(run)) {
      int heal = healAfter(run.events, publicationIndex(run.events, m));
      if (heal < 0) {
        continue;
      }
      boolean ackedBefore = false;
      for (int i = 0; i < heal; i++) {
        ackedBefore |=
            run.events.get(i) instanceof Ev.Ack a && a.m().equals(m) && a.type() == OpType.OK;
      }
      if (run.kind.isLog() || !ackedBefore) {
        exposed.add(m);
      }
    }
    return exposed;
  }

  private static int publicationIndex(List<Ev> events, Message m) {
    return indicesOf(events, ev -> ev instanceof Ev.Pub p && p.m().equals(m)).get(0);
  }

  /** Index of the stop of the first fault that starts after {@code from}, or -1. */
  private static int healAfter(List<Ev> events, int from) {
    for (int i = from + 1; i < events.size(); i++) {
      if (events.get(i) instanceof Ev.FaultStart start) {
        for (int j = i + 1; j < events.size(); j++) {
          if (events.get(j) instanceof Ev.FaultStop stop && stop.index() == start.index()) {
            return j;
          }
        }
      }
    }
    return -1;
  }

  private static boolean hasPublicationAfter(List<Ev> events, int index) {
    return events.subList(index + 1, events.size()).stream().anyMatch(ev -> ev instanceof Ev.Pub);
  }

  /**
   * Pairs of first-time, unflagged deliveries to one consumer of one publisher, with nothing in
   * between, both of confirmed messages (an unconfirmed message may legitimately arrive late).
   */
  private static List<int[]> orderedPairs(List<Ev> events) {
    Set<String> confirmed = new HashSet<>();
    for (Ev ev : events) {
      if (ev instanceof Ev.Pub p && p.outcome() == OpType.OK) {
        confirmed.add(p.m().uuid());
      }
    }
    Map<String, Integer> last = new HashMap<>();
    Set<String> seen = new HashSet<>();
    List<int[]> pairs = new ArrayList<>();
    for (int i = 0; i < events.size(); i++) {
      if (events.get(i) instanceof Ev.Del d
          && !d.redelivered()
          && seen.add(d.consumer() + d.m().uuid())) {
        String stream = d.consumer() + "/" + d.m().publisherId();
        if (!confirmed.contains(d.m().uuid())) {
          last.remove(stream);
          continue;
        }
        Integer previous = last.put(stream, i);
        if (previous != null) {
          pairs.add(new int[] {previous, i});
        }
      }
    }
    return pairs;
  }

  private static Operation publishOf(History history, Message m) {
    return history.operations().stream()
        .filter(
            op -> op.isInvoke() && op.value() instanceof Value.Publication p && p.msg().equals(m))
        .findFirst()
        .orElseThrow();
  }

  private static List<Operation> deliveriesOf(History history, Message m) {
    return history.operations().stream()
        .filter(
            op ->
                op.function() == OpFunction.DELIVER
                    && op.value() instanceof Value.Delivery d
                    && d.msg().equals(m))
        .toList();
  }

  private static Operation deliveryBy(History history, String consumer, Message m) {
    return deliveriesOf(history, m).stream()
        .filter(op -> op.process().equals(consumer) && !((Value.Delivery) op.value()).redelivered())
        .findFirst()
        .orElseThrow();
  }

  private static List<Long> evidenceOf(Verdict verdict, String kind) {
    return verdict.anomalies().stream()
        .filter(a -> a.kind().equals(kind))
        .flatMap(a -> a.evidence().stream())
        .toList();
  }
}
