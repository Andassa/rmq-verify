package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.checker.minimize.DeltaDebugger;
import io.github.andassa.rmqverify.checker.minimize.Minimization;
import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.nemesis.FaultEvent;
import io.github.andassa.rmqverify.nemesis.FaultPlan;
import io.github.andassa.rmqverify.workload.WorkloadException;
import java.io.IOException;
import java.io.Serial;
import java.time.Duration;
import java.util.List;

/**
 * Reduces a failing scenario: first the fault plan with ddmin (which faults are needed?), then the
 * load (how few publishers and how short a run still fail?). Every question is answered by real
 * runs through the oracle, repeated because the system under test is not deterministic.
 */
final class Minimizer {

  /** Answers "does this candidate still fail?", typically by running it several times. */
  @FunctionalInterface
  interface Oracle {
    boolean fails(FaultPlan plan, int publishers, Duration duration)
        throws ClusterException, WorkloadException, IOException, InterruptedException;
  }

  /**
   * Result of a minimisation.
   *
   * @param plan minimal plan
   * @param publishers smallest publisher count that still fails
   * @param duration shortest run that still fails
   * @param candidates number of distinct candidates evaluated
   */
  record Result(FaultPlan plan, int publishers, Duration duration, int candidates) {}

  private static final Duration TAIL = Duration.ofSeconds(10);
  private static final Duration SHORTEST = Duration.ofSeconds(20);

  private final Oracle oracle;
  private int extraCandidates;

  Minimizer(Oracle oracle) {
    this.oracle = oracle;
  }

  Result minimize(FaultPlan plan, int publishers, Duration duration)
      throws ClusterException, WorkloadException, IOException, InterruptedException {
    Minimization<FaultEvent> events;
    try {
      events =
          DeltaDebugger.minimize(
              plan.events(), candidate -> ask(plan.withEvents(candidate), publishers, duration));
    } catch (OracleFailure e) {
      throw e.rethrow();
    }
    FaultPlan reduced = plan.withEvents(events.result());
    int fewest = publishers;
    while (fewest > 1 && check(reduced, fewest / 2, duration)) {
      fewest /= 2;
    }
    Duration shortest = duration;
    Duration candidate = shortestCovering(reduced);
    if (candidate.compareTo(duration) < 0 && check(reduced, fewest, candidate)) {
      shortest = candidate;
    }
    FaultPlan finalPlan =
        new FaultPlan(
            reduced.schemaVersion(),
            reduced.seed(),
            shortest.toMillis(),
            reduced.nodes(),
            reduced.events());
    return new Result(finalPlan, fewest, shortest, events.tests() + extraCandidates);
  }

  private boolean check(FaultPlan plan, int publishers, Duration duration)
      throws ClusterException, WorkloadException, IOException, InterruptedException {
    extraCandidates++;
    return oracle.fails(plan, publishers, duration);
  }

  private boolean ask(FaultPlan plan, int publishers, Duration duration) {
    try {
      return oracle.fails(plan, publishers, duration);
    } catch (ClusterException | WorkloadException | IOException e) {
      throw new OracleFailure(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OracleFailure(e);
    }
  }

  /** The end of the last kept fault plus a tail for recovery, never below twenty seconds. */
  static Duration shortestCovering(FaultPlan plan) {
    long end = plan.events().stream().mapToLong(FaultEvent::endMillis).max().orElse(0);
    Duration covering = Duration.ofMillis(end).plus(TAIL);
    long seconds = (covering.toMillis() + 999) / 1000;
    Duration rounded = Duration.ofSeconds(seconds);
    return rounded.compareTo(SHORTEST) < 0 ? SHORTEST : rounded;
  }

  /** Carries a checked oracle failure through the predicate of the delta debugger. */
  private static final class OracleFailure extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial") // Never serialised: thrown and caught within one minimisation.
    private final Exception cause;

    OracleFailure(Exception cause) {
      super(cause);
      this.cause = cause;
    }

    /** Throws the original checked exception; returns only for an impossible unchecked cause. */
    IllegalStateException rethrow()
        throws ClusterException, WorkloadException, IOException, InterruptedException {
      if (cause instanceof ClusterException e) {
        throw e;
      }
      if (cause instanceof WorkloadException e) {
        throw e;
      }
      if (cause instanceof IOException e) {
        throw e;
      }
      if (cause instanceof InterruptedException e) {
        throw e;
      }
      return new IllegalStateException("unexpected oracle failure", cause);
    }
  }

  static List<String> describe(Result result) {
    return result.plan().events().stream()
        .map(e -> "#" + e.index() + " " + e.nemesis() + " " + e.describe())
        .toList();
  }
}
