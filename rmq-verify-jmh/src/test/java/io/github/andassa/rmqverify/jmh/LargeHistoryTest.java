package io.github.andassa.rmqverify.jmh;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.andassa.rmqverify.checker.CheckerContext;
import io.github.andassa.rmqverify.checker.Checkers;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.VerdictStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guard rail for the JMH numbers: one million operations are checked by the whole suite within a
 * generous bound, so that an accidental quadratic algorithm fails the build.
 */
class LargeHistoryTest {

  @Test
  void aMillionOperationsAreCheckedInSeconds() {
    History history = LargeHistories.generate(1_000_000, 7);
    assertThat(history.size()).isGreaterThanOrEqualTo(1_000_000);
    long start = System.nanoTime();
    List<Verdict> verdicts = Checkers.load().checkAll(history, CheckerContext.defaults());
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
    assertThat(verdicts)
        .hasSize(7)
        .allSatisfy(v -> assertThat(v.status()).as(v.checker()).isEqualTo(VerdictStatus.VALID));
    assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
  }

  @Test
  void benchmarkMethodsRunOnASmallHistory() {
    CheckerBenchmark benchmark = new CheckerBenchmark();
    benchmark.operations = 2_000;
    benchmark.checkerName = "LostMessages";
    benchmark.setUp();
    assertThat(benchmark.oneChecker().status()).isEqualTo(VerdictStatus.VALID);
    assertThat(benchmark.allCheckers()).hasSize(7);
  }
}
