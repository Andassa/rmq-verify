package io.github.andassa.rmqverify.cluster;

import java.util.Map;

/**
 * User name and password. {@link #toString()} never reveals the password, so credentials can be
 * logged by accident without leaking.
 *
 * @param user user name
 * @param password secret
 */
public record Credentials(String user, String password) {

  /** Validates that both parts are present. */
  public Credentials {
    if (user == null || user.isBlank() || password == null || password.isEmpty()) {
      throw new IllegalArgumentException("user and password are required");
    }
  }

  /**
   * Reads {@code <prefix>_USER} and {@code <prefix>_PASSWORD} from an environment.
   *
   * @param env environment variables, typically merged from {@code .env} and the process
   * @param prefix variable prefix, for example {@code RMQV_APP}
   * @return the credentials
   * @throws ClusterException if a variable is missing
   */
  public static Credentials from(Map<String, String> env, String prefix) throws ClusterException {
    String user = env.get(prefix + "_USER");
    String password = env.get(prefix + "_PASSWORD");
    if (user == null || password == null || user.isBlank() || password.isEmpty()) {
      throw new ClusterException(
          prefix + "_USER and " + prefix + "_PASSWORD must be set (run make up)");
    }
    return new Credentials(user, password);
  }

  @Override
  public String toString() {
    return "Credentials[user=" + user + ", password=***]";
  }
}
