package io.github.andassa.rmqverify.jmh;

import io.github.andassa.rmqverify.checker.Checker;
import io.github.andassa.rmqverify.checker.CheckerContext;
import io.github.andassa.rmqverify.checker.Checkers;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Verdict;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Time to check a history of one million operations, per checker and for the whole suite. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xmx4g"})
public class CheckerBenchmark {

  @Param({"1000000"})
  public int operations;

  @Param({
    "Availability",
    "ConfirmedButMissingAfterRecovery",
    "DuplicateDeliveries",
    "FifoOrdering",
    "LostMessages",
    "SplitBrainIndicators",
    "UnexpectedMessages"
  })
  public String checkerName;

  private History history;
  private Checker checker;
  private Checkers all;

  /** Builds the history once per trial. */
  @Setup(Level.Trial)
  public void setUp() {
    history = LargeHistories.generate(operations, 42);
    all = Checkers.load();
    checker = all.byName(checkerName).orElseThrow();
  }

  /**
   * Runs one checker.
   *
   * @return the verdict, so that the work is not eliminated
   */
  @Benchmark
  public Verdict oneChecker() {
    return checker.check(history, CheckerContext.defaults());
  }

  /**
   * Runs the seven checkers, as {@code rmq-verify check} does.
   *
   * @return the verdicts
   */
  @Benchmark
  public List<Verdict> allCheckers() {
    return all.checkAll(history, CheckerContext.defaults());
  }
}
