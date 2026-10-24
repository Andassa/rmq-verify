package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.cluster.Topology;
import io.github.andassa.rmqverify.nemesis.FaultPlan;
import io.github.andassa.rmqverify.nemesis.FaultPlanCodec;
import io.github.andassa.rmqverify.nemesis.NemesisRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code plan} prints or writes the fault plan a seed produces, without touching any cluster. */
@Command(
    name = "plan",
    mixinStandardHelpOptions = true,
    description = "Generate a fault plan from a seed.")
public final class PlanCommand implements Callable<Integer> {

  @Mixin FaultOptions faults;

  @Option(names = "--seed", required = true, description = "Seed of the plan.")
  long seed;

  @Option(
      names = "--duration",
      defaultValue = "60",
      description = "Run length in s (default: ${DEFAULT-VALUE}).")
  long durationSeconds;

  @Option(names = "--out", description = "Write plan.json here instead of standard output.")
  Path out;

  @Spec CommandSpec spec;

  @Override
  public Integer call() throws Exception {
    NemesisRegistry registry = NemesisRegistry.load();
    FaultPlan plan =
        faults.plan(
            registry, seed, Duration.ofSeconds(durationSeconds), Topology.local().nodeNames());
    FaultPlanCodec codec = new FaultPlanCodec(registry);
    if (out == null) {
      spec.commandLine().getOut().print(codec.write(plan));
      spec.commandLine().getOut().flush();
    } else {
      codec.write(plan, out);
    }
    return ExitCodes.VALID;
  }
}
