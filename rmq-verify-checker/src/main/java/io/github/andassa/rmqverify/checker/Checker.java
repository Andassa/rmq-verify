package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Verdict;

/**
 * Service provider interface of a consistency checker. Implementations are registered in {@code
 * META-INF/services/io.github.andassa.rmqverify.checker.Checker} and must be pure: the verdict may
 * only depend on the history and the context, so that it can be recomputed offline.
 */
public interface Checker {

  /**
   * Returns the stable name of the checker, used in reports and on the command line.
   *
   * @return the name, for example {@code LostMessages}
   */
  String name();

  /**
   * Judges a history.
   *
   * @param history the validated history of one run
   * @param context tunable thresholds
   * @return the verdict, with evidence for every violation
   */
  Verdict check(History history, CheckerContext context);
}
