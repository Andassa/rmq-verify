package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.cluster.Topology;
import io.github.andassa.rmqverify.nemesis.FaultPlan;
import io.github.andassa.rmqverify.nemesis.FaultPlanCodec;
import io.github.andassa.rmqverify.nemesis.NemesisRegistry;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code run} executes a scenario against the running cluster; {@code replay} does the same with a
 * fault plan read from {@code plan.json}, whose seed also drives the workload.
 */
@Command(
    name = "run",
    aliases = "replay",
    mixinStandardHelpOptions = true,
    description =
        "Run a workload under faults and check the history (alias: replay --plan plan.json).")
public final class RunCommand implements Callable<Integer> {

  private static final SecureRandom SEEDS = new SecureRandom();

  @Mixin RunOptions run;

  @Mixin FaultOptions faults;

  @Option(
      names = "--seed",
      description = "Seed of the workload and of the plan (default: random, recorded).")
  Long seed;

  @Option(names = "--plan", description = "Replay this plan.json instead of generating one.")
  Path plan;

  @Spec CommandSpec spec;

  ExperimentFactory experiments = CliContext::experiment;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = spec.commandLine().getOut();
    NemesisRegistry registry = NemesisRegistry.load();
    FaultPlan faultPlan = plan != null ? new FaultPlanCodec(registry).read(plan) : null;
    long effectiveSeed =
        seed != null ? seed : faultPlan != null ? faultPlan.seed() : SEEDS.nextLong();
    ExperimentSettings settings = run.settings(effectiveSeed);
    if (faultPlan == null) {
      faultPlan =
          faults.plan(registry, effectiveSeed, settings.duration(), Topology.local().nodeNames());
    }
    Experiment.RunOutcome outcome = experiments.create(run, out).run(settings, faultPlan);
    RunArtifacts.write(outcome.directory(), outcome.summary(), outcome.history());
    out.println("artefacts: " + outcome.directory().toAbsolutePath());
    return ExitCodes.of(VerdictTable.print(outcome.summary().verdicts(), out));
  }
}
