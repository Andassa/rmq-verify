package io.github.andassa.rmqverify.cluster;

/** Log hygiene for text that originates outside the JVM (broker, docker, exception messages). */
public final class Logs {

  private static final int MAX = 512;

  private Logs() {}

  /**
   * Neutralises line breaks and bounds the length, so that external text cannot forge log lines.
   *
   * @param text untrusted text, may be {@code null}
   * @return a single-line, bounded rendering
   */
  public static String safe(String text) {
    if (text == null) {
      return "null";
    }
    String single = text.replace('\r', ' ').replace('\n', ' ');
    return single.length() > MAX ? single.substring(0, MAX) + "..." : single;
  }
}
