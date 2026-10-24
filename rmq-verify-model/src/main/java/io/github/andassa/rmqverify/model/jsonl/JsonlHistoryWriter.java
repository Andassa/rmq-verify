package io.github.andassa.rmqverify.model.jsonl;

import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.Operation;
import io.github.andassa.rmqverify.model.OperationSink;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * Streams a history to JSONL: the header first, then one operation per line. Output is flushed
 * every few hundred operations to bound how much is lost if the process dies.
 */
public final class JsonlHistoryWriter implements OperationSink, Closeable {

  private static final int FLUSH_EVERY = 256;

  private final Writer out;
  private int unflushed;

  /**
   * Opens a writer on a stream and writes the header line.
   *
   * @param stream destination; closed by {@link #close()}
   * @param header header of the history
   * @throws IOException if the header cannot be written
   */
  public JsonlHistoryWriter(OutputStream stream, HistoryHeader header) throws IOException {
    this.out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
    writeLine(Codec.encodeHeader(header));
    out.flush();
  }

  /**
   * Opens a writer on a file, gzip-compressed when the name ends with {@code .gz}.
   *
   * @param path destination file, created or truncated
   * @param header header of the history
   * @return the writer
   * @throws IOException if the file cannot be created
   */
  public static JsonlHistoryWriter open(Path path, HistoryHeader header) throws IOException {
    OutputStream stream = Files.newOutputStream(path);
    try {
      Path name = path.getFileName();
      if (name != null && name.toString().endsWith(".gz")) {
        stream = new GZIPOutputStream(stream);
      }
      return new JsonlHistoryWriter(stream, header);
    } catch (IOException e) {
      stream.close();
      throw e;
    }
  }

  @Override
  public void accept(Operation operation) throws IOException {
    writeLine(Codec.encodeOperation(operation));
    if (++unflushed >= FLUSH_EVERY) {
      out.flush();
      unflushed = 0;
    }
  }

  private void writeLine(String json) throws IOException {
    out.write(json);
    out.write('\n');
  }

  @Override
  public void close() throws IOException {
    out.close();
  }
}
