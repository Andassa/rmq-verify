package io.github.andassa.rmqverify.checker;

import static io.github.andassa.rmqverify.model.testkit.HistoryFixture.msg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.model.Anomaly;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.VerdictStatus;
import io.github.andassa.rmqverify.model.testkit.HistoryFixture;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CheckerEdgeCasesTest {

  private static final CheckerContext CTX = CheckerContext.defaults();
  private static final List<String> NODES = List.of("rabbit1", "rabbit2", "rabbit3");

  @Test
  void emptyHistoryIsInconclusiveForEveryChecker() {
    History empty =
        new History(HistoryHeader.of("r", Map.of("q", QueueKind.QUORUM), Map.of()), List.of());
    for (Verdict verdict : Checkers.load().checkAll(empty, CTX)) {
      assertThat(verdict.status()).as(verdict.checker()).isEqualTo(VerdictStatus.UNKNOWN);
    }
  }

  @Test
  void registryLoadsTheSevenCheckersByName() {
    Checkers checkers = Checkers.load();
    assertThat(checkers.all())
        .extracting(Checker::name)
        .containsExactly(
            "Availability",
            "ConfirmedButMissingAfterRecovery",
            "DuplicateDeliveries",
            "FifoOrdering",
            "LostMessages",
            "SplitBrainIndicators",
            "UnexpectedMessages");
    assertThat(checkers.byName("LostMessages")).containsInstanceOf(LostMessagesChecker.class);
    assertThat(checkers.byName("Nope")).isEmpty();
    assertThatThrownBy(
            () -> Checkers.of(List.of(new LostMessagesChecker(), new LostMessagesChecker())))
        .hasMessageContaining("two checkers are named LostMessages");
    assertThat(Checkers.of(List.of(new FifoOrderingChecker())).all()).hasSize(1);
  }

  @Test
  void contextValidation() {
    Duration one = Duration.ofSeconds(1);
    assertThatThrownBy(() -> new CheckerContext(Duration.ZERO, one, one))
        .hasMessageContaining("availabilityWindow");
    assertThatThrownBy(() -> new CheckerContext(null, one, one))
        .hasMessageContaining("availabilityWindow");
    assertThatThrownBy(() -> new CheckerContext(one.negated(), one, one))
        .hasMessageContaining("availabilityWindow");
    assertThatThrownBy(() -> new CheckerContext(one, Duration.ZERO, one))
        .hasMessageContaining("maxRecovery");
    assertThatThrownBy(() -> new CheckerContext(one, null, one))
        .hasMessageContaining("maxRecovery");
    assertThatThrownBy(() -> new CheckerContext(one, one.negated(), one))
        .hasMessageContaining("maxRecovery");
    assertThatThrownBy(() -> new CheckerContext(one, one, one.negated()))
        .hasMessageContaining("settle");
    assertThatThrownBy(() -> new CheckerContext(one, one, null)).hasMessageContaining("settle");
    assertThat(new CheckerContext(one, one, Duration.ZERO).settle()).isZero();
  }

  @Test
  void undrainedQueueMakesLossesUnverifiable() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.published(msg("p", "q", 0), OpType.OK);
    f.drainStart("q");
    f.drainEnd("q", OpType.INFO, 0);
    Verdict verdict = new LostMessagesChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.UNKNOWN);
    assertThat(verdict.anomalies()).extracting(Anomaly::kind).containsExactly("LOST_UNVERIFIABLE");
    assertThat(verdict.metrics())
        .containsEntry("unverifiable", 1.0)
        .containsEntry("lostConfirmed", 0.0);
  }

  @Test
  void indeterminateMessagesAreCountedNotLost() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Message survived = msg("p", "q", 0);
    f.published(survived, OpType.INFO);
    f.published(msg("p", "q", 1), OpType.INFO);
    f.publish(msg("p", "q", 2));
    f.published(msg("p", "q", 3), OpType.FAIL);
    f.consumed("c", survived, false, 1);
    f.drainStart("q");
    f.drainEnd("q", OpType.OK, 0);
    Verdict verdict = new LostMessagesChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.VALID);
    assertThat(verdict.metrics())
        .containsEntry("indeterminate", 3.0)
        .containsEntry("indeterminateSurvived", 1.0)
        .containsEntry("indeterminateNotDelivered", 2.0)
        .containsEntry("failed", 1.0)
        .containsEntry("confirmed", 0.0)
        .containsEntry("published", 4.0);
    assertThat(verdict.summary()).isEqualTo("0 lost of 0 confirmed; 3 indeterminate (1 survived)");
  }

  @Test
  void drainRetriedAfterAFailureCountsAsDrained() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.published(msg("p", "q", 0), OpType.OK);
    f.drainStart("q");
    f.drainEnd("q", OpType.OK, 0);
    f.drainStart("q");
    f.drainEnd("q", OpType.INFO, 0);
    MessageLedger ledger = MessageLedger.of(f.build());
    assertThat(ledger.drained("q")).isTrue();
    assertThat(ledger.drainCompletion("q").orElseThrow().type()).isEqualTo(OpType.OK);
    assertThat(new LostMessagesChecker().check(f.build(), CTX).status())
        .isEqualTo(VerdictStatus.INVALID);
  }

  @Test
  void ledgerAnswersQueriesAboutUnknownThings() {
    HistoryFixture f = HistoryFixture.stream("s");
    f.ack("c", msg("ghost", "s", 0), false, 1, OpType.OK);
    f.faultStop(3, "kill", "rabbit1");
    MessageLedger ledger = MessageLedger.of(f.build());
    assertThat(ledger.kind("s")).contains(QueueKind.STREAM);
    assertThat(ledger.kind("nope")).isEmpty();
    assertThat(ledger.isLog("s")).isTrue();
    assertThat(ledger.isLog("nope")).isFalse();
    assertThat(ledger.drained("s")).isFalse();
    assertThat(ledger.drainCompletion("s")).isEmpty();
    assertThat(ledger.faults()).isEmpty();
    assertThat(ledger.record("ghost/s/0")).isEmpty();
    assertThat(ledger.hasPublications()).isFalse();
    assertThat(ledger.history().size()).isEqualTo(3);
  }

  @Test
  void faultWindowGeometry() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Operation start = f.faultStart(0, "pause", "rabbit2");
    f.advance(Duration.ofSeconds(1));
    Operation stop = f.faultStop(0, "pause", "rabbit2");
    f.faultStart(1, "kill", "rabbit1");
    List<FaultWindow> windows = MessageLedger.of(f.build()).faults();
    FaultWindow closed = windows.get(0);
    FaultWindow open = windows.get(1);
    assertThat(closed.closed()).isTrue();
    assertThat(open.closed()).isFalse();
    assertThat(closed.endNanos(Long.MAX_VALUE)).isEqualTo(stop.timestampNanos());
    assertThat(open.endNanos(42)).isEqualTo(42);
    assertThat(closed.covers(start.timestampNanos(), 0, 0)).isTrue();
    assertThat(closed.covers(start.timestampNanos() - 1, 0, 0)).isFalse();
    assertThat(closed.covers(stop.timestampNanos(), 0, 0)).isTrue();
    assertThat(closed.covers(stop.timestampNanos() + 1, 0, 0)).isFalse();
    assertThat(closed.covers(stop.timestampNanos() + 5, 5, 0)).isTrue();
  }

  @Test
  void unexpectedMessagesClassifiesEveryCase() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Message failed = msg("p", "q", 0);
    f.published(failed, OpType.FAIL);
    f.deliver("c", failed, false, 1);
    Message published = msg("p", "q", 1);
    f.published(published, OpType.OK);
    f.deliver("c", new Message(HistoryFixture.RUN, "p", "q", 7, published.uuid()), false, 2);
    f.deliver("c", new Message("old-run", "p", "q", 0, "old"), false, 3);
    f.deliver("c", new Message(HistoryFixture.RUN, "p", "q", 9, "never"), false, 4);
    f.deliverCorrupt("c", "q", "abc", 5);
    Verdict verdict = new UnexpectedMessagesChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.INVALID);
    assertThat(verdict.anomalies())
        .extracting(Anomaly::kind)
        .containsExactlyInAnyOrder(
            "UNEXPECTED_CORRUPT",
            "UNEXPECTED_UNKNOWN",
            "FOREIGN_RUN",
            "UNEXPECTED_MISMATCH",
            "FAILED_BUT_DELIVERED");
    assertThat(verdict.metrics()).containsEntry("deliveries", 5.0).containsEntry("foreign", 1.0);
    assertThat(verdict.summary()).isEqualTo("3 unexpected of 5 deliveries");
  }

  @Test
  void flaggedRedeliveryIsANoteAndUnflaggedIsAViolation() {
    HistoryFixture f = HistoryFixture.classic("q");
    Message m = msg("p", "q", 0);
    f.published(m, OpType.OK);
    f.deliver("c1", m, false, 1);
    f.deliver("c2", m, true, 1);
    Verdict ok = new DuplicateDeliveriesChecker().check(f.build(), CTX);
    assertThat(ok.status()).isEqualTo(VerdictStatus.VALID);
    assertThat(ok.anomalies()).extracting(Anomaly::kind).containsExactly("REDELIVERY");
    assertThat(ok.metrics())
        .containsEntry("legitimateRedeliveries", 1.0)
        .containsEntry("duplicatedMessages", 1.0);
    f.deliver("c2", m, false, 2);
    Verdict bad = new DuplicateDeliveriesChecker().check(f.build(), CTX);
    assertThat(bad.anomalies()).extracting(Anomaly::kind).containsExactly("DUPLICATE_UNEXPECTED");
    assertThat(bad.metrics()).containsEntry("unexpectedDuplicates", 1.0);
    assertThat(bad.anomalies().get(0).description())
        .contains("1 repeat(s) without the redelivered flag");
  }

  @Test
  void streamReadersAtTheSameOffsetAreNotDuplicates() {
    HistoryFixture f = HistoryFixture.stream("s");
    Message m = msg("p", "s", 0);
    f.published(m, OpType.OK);
    f.deliver("c1", m, false, 0);
    f.deliver("c2", m, false, 0);
    f.drainStart("s");
    f.deliver(HistoryFixture.drainProcess("s"), m, false, 0);
    f.drainEnd("s", OpType.OK, 1);
    Verdict verdict = new DuplicateDeliveriesChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.VALID);
    assertThat(verdict.anomalies()).isEmpty();
    assertThat(verdict.metrics()).containsEntry("duplicatedMessages", 0.0);
  }

  @Test
  void aStreamReaderReplayingAnOffsetIsANote() {
    HistoryFixture f = HistoryFixture.stream("s");
    Message m = msg("p", "s", 0);
    f.published(m, OpType.OK);
    f.deliver("c1", m, false, 0);
    f.deliver("c1", m, false, 0);
    Verdict verdict = new DuplicateDeliveriesChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.VALID);
    assertThat(verdict.anomalies()).extracting(Anomaly::kind).containsExactly("OFFSET_REPLAY");
    assertThat(verdict.metrics())
        .containsEntry("duplicatedMessages", 1.0)
        .containsEntry("legitimateRedeliveries", 1.0);
    f.deliver("c2", m, false, 9);
    Verdict twice = new DuplicateDeliveriesChecker().check(f.build(), CTX);
    assertThat(twice.anomalies()).extracting(Anomaly::kind).containsExactly("DUPLICATE_IN_LOG");
  }

  @Test
  void fifoGapRequiresADrainedQueue() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Message lost = msg("p", "q", 0);
    Message kept = msg("p", "q", 1);
    f.published(lost, OpType.OK);
    f.published(kept, OpType.OK);
    f.consumed("c", kept, false, 1);
    Verdict undrained = new FifoOrderingChecker().check(f.build(), CTX);
    assertThat(undrained.status()).isEqualTo(VerdictStatus.VALID);
    assertThat(undrained.metrics()).containsEntry("gapChecksSkippedUndrained", 1.0);
    f.drainStart("q");
    f.drainEnd("q", OpType.OK, 0);
    Verdict drained = new FifoOrderingChecker().check(f.build(), CTX);
    assertThat(drained.anomalies()).extracting(Anomaly::kind).containsExactly("FIFO_GAP");
    assertThat(drained.metrics()).containsEntry("gaps", 1.0);
  }

  @Test
  void fifoIgnoresUnconfirmedHolesAndTrailingLosses() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.published(msg("p", "q", 0), OpType.INFO);
    Message kept = msg("p", "q", 1);
    f.published(kept, OpType.OK);
    f.published(msg("p", "q", 2), OpType.OK);
    f.consumed("c", kept, false, 1);
    f.drainStart("q");
    f.drainEnd("q", OpType.OK, 0);
    Verdict verdict = new FifoOrderingChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.VALID);
    assertThat(verdict.metrics()).containsEntry("orderedDeliveries", 1.0);
  }

  @Test
  void fifoIgnoresUnknownAndCorruptDeliveries() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.deliver("c", msg("ghost", "q", 5), false, 1);
    f.deliverCorrupt("c", "q", "x", 2);
    Verdict verdict = new FifoOrderingChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.VALID);
    assertThat(verdict.metrics()).containsEntry("orderedDeliveries", 0.0);
  }

  @Test
  void missingAfterRecoveryNeedsAClosedWindowAndADrain() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.published(msg("p", "q", 0), OpType.OK);
    f.faultStart(0, "kill", "rabbit1");
    Verdict open = new ConfirmedButMissingAfterRecoveryChecker().check(f.build(), CTX);
    assertThat(open.status()).isEqualTo(VerdictStatus.UNKNOWN);
    assertThat(open.anomalies()).extracting(Anomaly::kind).containsExactly("RECOVERY_UNVERIFIABLE");
    f.faultStop(0, "kill", "rabbit1");
    f.drainStart("q");
    f.drainEnd("q", OpType.OK, 0);
    Verdict closed = new ConfirmedButMissingAfterRecoveryChecker().check(f.build(), CTX);
    assertThat(closed.anomalies())
        .extracting(Anomaly::kind)
        .containsExactly("MISSING_AFTER_RECOVERY");
    assertThat(closed.metrics()).containsEntry("exposedMessages", 1.0).containsEntry("faults", 1.0);
  }

  @Test
  void messageAcknowledgedBeforeTheHealIsNotExpectedAfterIt() {
    HistoryFixture f = HistoryFixture.quorum("q");
    Message m = msg("p", "q", 0);
    f.published(m, OpType.OK);
    f.faultStart(0, "kill", "rabbit1");
    f.consumed("c", m, false, 1);
    f.faultStop(0, "kill", "rabbit1");
    f.published(msg("p", "q", 1), OpType.FAIL);
    f.drainStart("q");
    f.drainEnd("q", OpType.OK, 0);
    Verdict verdict = new ConfirmedButMissingAfterRecoveryChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.VALID);
    assertThat(verdict.metrics()).containsEntry("exposedMessages", 0.0);
  }

  @Test
  void streamMessagesAreAlwaysExpectedAfterRecovery() {
    HistoryFixture f = HistoryFixture.stream("s");
    Message m = msg("p", "s", 0);
    f.published(m, OpType.OK);
    f.faultStart(0, "kill", "rabbit1");
    f.deliver("c", m, false, 0);
    f.faultStop(0, "kill", "rabbit1");
    f.drainStart("s");
    f.drainEnd("s", OpType.OK, 0);
    Verdict verdict = new ConfirmedButMissingAfterRecoveryChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.INVALID);
  }

  @Test
  void availabilityWithoutRecovery() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.published(msg("p", "q", 0), OpType.OK);
    f.faultStart(0, "kill", "rabbit1");
    f.faultStop(0, "kill", "rabbit1");
    f.faultStart(1, "kill", "rabbit1");
    f.published(msg("p", "q", 1), OpType.INFO);
    f.faultStop(1, "kill", "rabbit1");
    f.published(msg("p", "q", 2), OpType.FAIL);
    f.faultStart(2, "kill", "rabbit2");
    Verdict verdict = new AvailabilityChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.INVALID);
    assertThat(verdict.anomalies())
        .extracting(Anomaly::kind)
        .containsExactly("NOT_RECOVERED", "NOT_RECOVERED");
    assertThat(verdict.metrics())
        .containsEntry("recoveriesMeasured", 0.0)
        .containsEntry("meanRecoveryMs", 0.0);
  }

  @Test
  void availabilityMeasuresWindowsAndRecovery() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.published(msg("p", "q", 0), OpType.OK);
    f.advance(Duration.ofMillis(1500));
    f.faultStart(0, "pause", "rabbit1");
    f.published(msg("p", "q", 1), OpType.INFO);
    f.advance(Duration.ofSeconds(1));
    f.published(msg("p", "q", 2), OpType.INFO);
    f.advance(Duration.ofSeconds(1));
    f.faultStop(0, "pause", "rabbit1");
    f.advance(Duration.ofMillis(300));
    f.published(msg("p", "q", 3), OpType.OK);
    Verdict verdict = new AvailabilityChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.VALID);
    assertThat(verdict.metrics())
        .containsEntry("successRate", 0.5)
        .containsEntry("unavailableWindows", 2.0)
        .containsEntry("maxUnavailabilityMs", 2000.0)
        .containsEntry("recoveriesMeasured", 1.0)
        .containsEntry("windows", 4.0);
    assertThat(verdict.metrics().get("recoveryMs.0")).isBetween(300.0, 303.0);
    assertThat(verdict.metrics().get("maxRecoveryMs"))
        .isEqualTo(verdict.metrics().get("meanRecoveryMs"));
    assertThat(verdict.summary())
        .isEqualTo("50.0% of publishes confirmed, longest unavailability 2000 ms");
  }

  @Test
  void availabilityNotesUnobservedRecovery() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.published(msg("p", "q", 0), OpType.OK);
    f.faultStart(0, "kill", "rabbit1");
    f.faultStop(0, "kill", "rabbit1");
    Verdict verdict = new AvailabilityChecker().check(f.build(), CTX);
    assertThat(verdict.status()).isEqualTo(VerdictStatus.VALID);
    assertThat(verdict.anomalies())
        .extracting(Anomaly::kind)
        .containsExactly("RECOVERY_UNOBSERVED");
  }

  @Test
  void splitBrainIndicatorsAfterTheHealAreViolations() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.faultStart(0, "partition-halves", "rabbit1");
    f.probe(0, "rabbit1", "q", "rabbit1", List.of("rabbit1"), List.of("rabbit2"));
    f.probe(0, "rabbit2", "q", "rabbit2", NODES, List.of());
    f.faultStop(0, "partition-halves", "rabbit1");
    Verdict during = new SplitBrainIndicatorsChecker().check(f.build(), CTX);
    assertThat(during.status()).isEqualTo(VerdictStatus.VALID);
    assertThat(during.anomalies())
        .extracting(Anomaly::kind)
        .containsExactly("LEADER_CONFLICT", "VIEW_DIVERGENCE", "PARTITION_REPORTED");
    f.advance(Duration.ofSeconds(16));
    f.probe(1, "rabbit1", "cluster", "", List.of("rabbit1"), List.of("rabbit3"));
    f.probe(1, "rabbit3", "cluster", "", NODES, List.of());
    Verdict after = new SplitBrainIndicatorsChecker().check(f.build(), CTX);
    assertThat(after.status()).isEqualTo(VerdictStatus.INVALID);
    assertThat(after.anomalies())
        .filteredOn(Anomaly::isViolation)
        .extracting(Anomaly::kind)
        .containsExactly("VIEW_DIVERGENCE", "PARTITION_REPORTED");
    assertThat(after.metrics()).containsEntry("probeRounds", 2.0).containsEntry("quietRounds", 1.0);
  }

  @Test
  void probesWithinTheSettleDelayAreTolerated() {
    HistoryFixture f = HistoryFixture.quorum("q");
    f.faultStart(0, "pause", "rabbit1");
    f.faultStop(0, "pause", "rabbit1");
    f.advance(Duration.ofSeconds(14));
    f.probe(0, "rabbit1", "q", "rabbit1", NODES, List.of());
    f.probe(0, "rabbit2", "q", "rabbit2", NODES, List.of());
    assertThat(new SplitBrainIndicatorsChecker().check(f.build(), CTX).status())
        .isEqualTo(VerdictStatus.VALID);
  }
}
