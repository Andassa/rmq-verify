package io.github.andassa.rmqverify.model.jsonl;

import java.io.IOException;
import java.io.Reader;

/**
 * Reads lines without ever buffering more than a fixed number of characters, so that a hostile file
 * made of one huge line cannot exhaust memory.
 */
final class BoundedLineReader {

  private final Reader reader;
  private final int maxLineLength;
  private long lineNumber;

  BoundedLineReader(Reader reader, int maxLineLength) {
    this.reader = reader;
    this.maxLineLength = maxLineLength;
  }

  /** Returns the next line without its terminator, or {@code null} at end of input. */
  String next() throws IOException {
    StringBuilder line = new StringBuilder();
    int c = reader.read();
    if (c < 0) {
      return null;
    }
    lineNumber++;
    while (c >= 0 && c != '\n') {
      if (c != '\r') {
        if (line.length() == maxLineLength) {
          throw new HistoryFormatException(
              lineNumber, "line longer than " + maxLineLength + " characters");
        }
        line.append((char) c);
      }
      c = reader.read();
    }
    return line.toString();
  }

  long lineNumber() {
    return lineNumber;
  }
}
