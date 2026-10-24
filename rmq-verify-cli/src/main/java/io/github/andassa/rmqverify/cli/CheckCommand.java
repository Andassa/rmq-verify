package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.checker.CheckerContext;
import io.github.andassa.rmqverify.checker.Checkers;
import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.jsonl.JsonlHistoryReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code check} recomputes every verdict from a history file, offline. */
@Command(
    name = "check",
    mixinStandardHelpOptions = true,
    description = "Check a history.jsonl offline.")
public final class CheckCommand implements Callable<Integer> {

  @Parameters(index = "0", description = "history.jsonl or history.jsonl.gz")
  Path history;

  @Option(names = "--checker", split = ",", description = "Only these checkers (default: all).")
  List<String> only;

  @Option(
      names = "--max-recovery",
      defaultValue = "60",
      description = "Accepted recovery time in s (default: ${DEFAULT-VALUE}).")
  long maxRecoverySeconds;

  @Spec CommandSpec spec;

  @Override
  public Integer call() throws Exception {
    History h = new JsonlHistoryReader().read(history);
    Checkers all = Checkers.load();
    Checkers selected =
        only == null || only.isEmpty()
            ? all
            : Checkers.of(
                only.stream()
                    .map(
                        name ->
                            all.byName(name)
                                .orElseThrow(
                                    () -> new IllegalArgumentException("unknown checker " + name)))
                    .toList());
    CheckerContext context =
        new CheckerContext(
            Duration.ofSeconds(1), Duration.ofSeconds(maxRecoverySeconds), Duration.ofSeconds(15));
    List<Verdict> verdicts = selected.checkAll(h, context);
    spec.commandLine()
        .getOut()
        .printf("%s: %d operations, run %s%n", history, h.size(), h.header().runId());
    return ExitCodes.of(VerdictTable.print(verdicts, spec.commandLine().getOut()));
  }
}
