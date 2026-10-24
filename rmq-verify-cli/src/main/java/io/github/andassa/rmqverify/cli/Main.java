package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.cluster.Logs;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Entry point of the {@code rmq-verify} command line. */
@Command(
    name = "rmq-verify",
    mixinStandardHelpOptions = true,
    versionProvider = Main.Version.class,
    description = "Jepsen-style verification of RabbitMQ delivery guarantees under faults.",
    subcommands = {RunCommand.class, PlanCommand.class, CheckCommand.class, ReportCommand.class})
public final class Main {

  private Main() {}

  /**
   * Runs the command line.
   *
   * @param args command line arguments
   */
  public static void main(String[] args) {
    System.exit(commandLine().execute(args));
  }

  static CommandLine commandLine() {
    CommandLine cli = new CommandLine(new Main());
    cli.setExecutionExceptionHandler(
        (e, line, parsed) -> {
          line.getErr().println("rmq-verify: " + Logs.safe(e.getMessage()));
          line.getErr().flush();
          return ExitCodes.FAILURE;
        });
    return cli;
  }

  /** Reports the version recorded in the jar manifest. */
  static final class Version implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      String version = Main.class.getPackage().getImplementationVersion();
      return new String[] {"rmq-verify " + (version == null ? "development" : version)};
    }
  }
}
