package io.github.andassa.rmqverify.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Scripted {@link ProcessRunner} emulating the docker CLI for the three-node test topology. It
 * keeps container state (running, paused) so that lifecycle commands are observable, and records
 * every command it receives.
 */
final class FakeDocker implements ProcessRunner {

  final List<List<String>> commands = new ArrayList<>();
  final Map<String, Boolean> running = new HashMap<>();
  final Map<String, Boolean> paused = new HashMap<>();
  final Map<String, String> labels = new HashMap<>();
  String networkLabel = "rmqv";
  Function<List<String>, ProcessResult> execHandler = c -> new ProcessResult(0, "", "");

  FakeDocker() {
    for (int i = 1; i <= 3; i++) {
      running.put("rmqv-rabbit" + i, true);
      paused.put("rmqv-rabbit" + i, false);
    }
  }

  @Override
  public ProcessResult run(List<String> command, Duration timeout) {
    commands.add(List.copyOf(command));
    String verb = command.get(1);
    String target = command.get(command.size() - 1);
    return switch (verb) {
      case "inspect" -> inspect(target);
      case "network" ->
          networkLabel == null
              ? new ProcessResult(1, "", "no such network")
              : new ProcessResult(
                  0,
                  "[{\"Labels\":{\"io.github.andassa.rmq-verify.cluster\":\""
                      + networkLabel
                      + "\"}}]",
                  "");
      case "kill", "stop" -> set(running, target, false);
      case "start" -> set(running, target, true);
      case "pause" -> set(paused, target, true);
      case "unpause" -> set(paused, target, false);
      case "exec" -> execHandler.apply(command);
      default -> new ProcessResult(125, "", "unknown verb " + verb);
    };
  }

  private ProcessResult set(Map<String, Boolean> state, String container, boolean value) {
    if (!state.containsKey(container)) {
      return new ProcessResult(1, "", "No such container: " + container);
    }
    state.put(container, value);
    return new ProcessResult(0, container, "");
  }

  private ProcessResult inspect(String container) {
    if (!running.containsKey(container)) {
      return new ProcessResult(1, "[]", "Error: No such object: " + container);
    }
    int n = container.charAt(container.length() - 1) - '0';
    String cluster = labels.getOrDefault(container, "rmqv");
    String json =
        "[{\"Name\":\"/"
            + container
            + "\",\"State\":{\"Running\":"
            + running.get(container)
            + ",\"Paused\":"
            + paused.get(container)
            + "},\"Config\":{\"Labels\":{"
            + "\"io.github.andassa.rmq-verify.cluster\":\""
            + cluster
            + "\","
            + "\"io.github.andassa.rmq-verify.role\":\"node\"}},"
            + "\"NetworkSettings\":{\"Networks\":{\"rmqv-net\":{\"IPAddress\":\"172.20.0."
            + (10 + n)
            + "\"}}}}]";
    return new ProcessResult(0, json, "");
  }

  List<List<String>> execs() {
    return commands.stream().filter(c -> c.get(1).equals("exec")).toList();
  }
}
