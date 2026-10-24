package io.github.andassa.rmqverify.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strict reader of {@code KEY=VALUE} files such as {@code .env}. No interpolation, no quoting, no
 * command substitution: values are taken literally.
 */
public final class EnvFile {

  private static final Pattern KEY = Pattern.compile("[A-Z_][A-Z0-9_]{0,63}");
  private static final long MAX_SIZE = 64 * 1024;

  private EnvFile() {}

  /**
   * Parses a file.
   *
   * @param path the file
   * @return the variables, empty if the file does not exist
   * @throws ClusterException if the file is too large or malformed
   */
  public static Map<String, String> read(Path path) throws ClusterException {
    if (!Files.exists(path)) {
      return Map.of();
    }
    try {
      if (Files.size(path) > MAX_SIZE) {
        throw new ClusterException(path + " is larger than " + MAX_SIZE + " bytes");
      }
      return parse(Files.readAllLines(path, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new ClusterException("cannot read " + path, e);
    }
  }

  static Map<String, String> parse(List<String> lines) throws ClusterException {
    Map<String, String> values = new HashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int eq = line.indexOf('=');
      if (eq <= 0 || !KEY.matcher(line.substring(0, eq)).matches()) {
        throw new ClusterException("line " + (i + 1) + " is not KEY=VALUE");
      }
      values.put(line.substring(0, eq), line.substring(eq + 1));
    }
    return values;
  }

  /**
   * Merges a file with the process environment; process variables win.
   *
   * @param path the file
   * @param processEnv the process environment
   * @return the merged variables
   * @throws ClusterException if the file is malformed
   */
  public static Map<String, String> withProcessEnv(Path path, Map<String, String> processEnv)
      throws ClusterException {
    Map<String, String> merged = new HashMap<>(read(path));
    merged.putAll(processEnv);
    return merged;
  }
}
