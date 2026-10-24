package io.github.andassa.rmqverify.model.jsonl;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.InvalidHistoryException;
import io.github.andassa.rmqverify.model.Operation;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Strict reader of JSONL histories. Input is untrusted: every limit is enforced before memory is
 * allocated for it, every field is type-checked, unknown fields are rejected and the resulting
 * history is validated against the domain invariants.
 */
public final class JsonlHistoryReader {

  /** Default maximum length of one line, in characters. */
  public static final int DEFAULT_MAX_LINE_LENGTH = 64 * 1024;

  /** Default maximum number of operations in one history. */
  public static final int DEFAULT_MAX_OPERATIONS = 20_000_000;

  private final int maxLineLength;
  private final int maxOperations;

  /** Creates a reader with the default limits. */
  public JsonlHistoryReader() {
    this(DEFAULT_MAX_LINE_LENGTH, DEFAULT_MAX_OPERATIONS);
  }

  /**
   * Creates a reader with explicit limits.
   *
   * @param maxLineLength maximum characters per line
   * @param maxOperations maximum number of operations
   */
  public JsonlHistoryReader(int maxLineLength, int maxOperations) {
    if (maxLineLength <= 0 || maxOperations <= 0) {
      throw new IllegalArgumentException("limits must be positive");
    }
    this.maxLineLength = maxLineLength;
    this.maxOperations = maxOperations;
  }

  /**
   * Reads a history file, transparently decompressing gzip content.
   *
   * @param path the file
   * @return the validated history
   * @throws IOException if the file cannot be read or is not a valid history
   */
  public History read(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      return read(in);
    }
  }

  /**
   * Reads a history from a stream, transparently decompressing gzip content.
   *
   * @param stream the input; not closed by this method
   * @return the validated history
   * @throws IOException if the stream cannot be read or is not a valid history
   */
  public History read(InputStream stream) throws IOException {
    InputStream in = maybeGunzip(new BufferedInputStream(stream));
    InputStreamReader decoder =
        new InputStreamReader(
            in,
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
    BoundedLineReader lines = new BoundedLineReader(decoder, maxLineLength);
    HistoryHeader header = null;
    List<Operation> operations = new ArrayList<>();
    for (String line = lines.next(); line != null; line = lines.next()) {
      if (line.isBlank()) {
        continue;
      }
      Fields fields =
          new Fields(JsonTree.parseObject(line, lines.lineNumber()), lines.lineNumber());
      if (header == null) {
        header = Codec.decodeHeader(fields);
      } else {
        if (operations.size() == maxOperations) {
          throw new HistoryFormatException(
              lines.lineNumber(), "more than " + maxOperations + " operations");
        }
        operations.add(Codec.decodeOperation(fields));
      }
    }
    if (header == null) {
      throw new HistoryFormatException(0, "empty input: the header line is missing");
    }
    try {
      return new History(header, operations);
    } catch (InvalidHistoryException e) {
      throw new HistoryFormatException(0, "invalid history: " + e.getMessage(), e);
    }
  }

  private static InputStream maybeGunzip(BufferedInputStream in) throws IOException {
    in.mark(2);
    int first = in.read();
    int second = in.read();
    in.reset();
    boolean gzip = first == 0x1f && second == 0x8b;
    return gzip ? new GZIPInputStream(in) : in;
  }
}
