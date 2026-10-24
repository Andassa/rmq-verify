package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.VerdictStatus;
import io.github.andassa.rmqverify.nemesis.FaultPlan;
import io.github.andassa.rmqverify.nemesis.FaultPlanCodec;
import io.github.andassa.rmqverify.nemesis.NemesisRegistry;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code minimize} shrinks a failing scenario to the smallest fault plan and load that still make
 * the chosen checker fail in at least one of {@code --repeats} runs, and writes a replayable plan.
 */
@Command(
    name = "minimize",
    mixinStandardHelpOptions = true,
    description = "Reduce a failing plan.json with delta debugging (ddmin), then reduce the load.")
public final class MinimizeCommand implements Callable<Integer> {

  @Mixin RunOptions run;

  @Option(names = "--plan", required = true, description = "plan.json of the failing run.")
  Path plan;

  @Option(
      names = "--checker",
      required = true,
      description = "Checker whose INVALID verdict must be reproduced.")
  String checker;

  @Option(
      names = "--repeats",
      defaultValue = "2",
      description = "Runs per candidate; one failure suffices (default: ${DEFAULT-VALUE}).")
  int repeats;

  @Spec CommandSpec spec;

  ExperimentFactory experiments = CliContext::experiment;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = spec.commandLine().getOut();
    NemesisRegistry registry = NemesisRegistry.load();
    FaultPlanCodec codec = new FaultPlanCodec(registry);
    FaultPlan original = codec.read(plan);
    Path root =
        run.out != null
            ? run.out
            : Path.of(
                "runs",
                "minimize-"
                    + ZonedDateTime.now(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
    Experiment experiment = experiments.create(run, out);
    AtomicInteger candidate = new AtomicInteger();
    Minimizer.Oracle oracle =
        (candidatePlan, publishers, duration) -> {
          int n = candidate.incrementAndGet();
          out.printf(
              "candidate %d: %d fault(s), %d publisher(s), %d s%n",
              n, candidatePlan.events().size(), publishers, duration.toSeconds());
          for (int rep = 1; rep <= repeats; rep++) {
            run.out = root.resolve("candidate-" + n + "-" + rep);
            run.publishers = publishers;
            run.durationSeconds = duration.toSeconds();
            run.repetition = rep;
            Experiment.RunOutcome outcome =
                experiment.run(run.settings(original.seed()), candidatePlan);
            RunArtifacts.write(outcome.directory(), outcome.summary(), outcome.history());
            boolean failed =
                outcome.summary().verdicts().stream()
                    .anyMatch(
                        (Verdict v) ->
                            v.checker().equals(checker) && v.status() == VerdictStatus.INVALID);
            out.printf("  repetition %d: %s%n", rep, failed ? "reproduced" : "not reproduced");
            if (failed) {
              return true;
            }
          }
          return false;
        };
    Minimizer.Result result =
        new Minimizer(oracle)
            .minimize(original, run.publishers, Duration.ofSeconds(run.durationSeconds));
    Files.createDirectories(root);
    Path minimized = root.resolve("minimized-plan.json");
    codec.write(result.plan(), minimized);
    out.printf(
        "minimal scenario after %d candidate(s): %d of %d fault(s), %d publisher(s), %d s%n",
        result.candidates(),
        result.plan().events().size(),
        original.events().size(),
        result.publishers(),
        result.duration().toSeconds());
    Minimizer.describe(result).forEach(line -> out.println("  " + line));
    out.printf(
        "replay: rmq-verify replay --plan %s --queue-type %s --queues %d --publishers %d --consumers %d"
            + " --rate %s --duration %d --max-recovery %d%s%n",
        minimized,
        run.queueType,
        run.queues,
        result.publishers(),
        run.consumers,
        run.rate,
        result.duration().toSeconds(),
        run.maxRecoverySeconds,
        run.maxLength > 0 ? " --max-length " + run.maxLength : "");
    return ExitCodes.VALID;
  }
}
