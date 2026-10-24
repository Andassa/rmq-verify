package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.VerdictStatus;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/** Plain-text verdict table for terminals. */
final class VerdictTable {

  private VerdictTable() {}

  static VerdictStatus print(List<Verdict> verdicts, PrintWriter out) {
    out.printf(Locale.ROOT, "%-34s %-8s %s%n", "CHECKER", "VERDICT", "SUMMARY");
    VerdictStatus overall = VerdictStatus.VALID;
    for (Verdict v : verdicts) {
      out.printf(Locale.ROOT, "%-34s %-8s %s%n", v.checker(), v.status(), v.summary());
      if (v.status() == VerdictStatus.INVALID) {
        overall = VerdictStatus.INVALID;
      } else if (v.status() == VerdictStatus.UNKNOWN && overall == VerdictStatus.VALID) {
        overall = VerdictStatus.UNKNOWN;
      }
    }
    out.flush();
    return overall;
  }
}
