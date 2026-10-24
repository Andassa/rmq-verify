package io.github.andassa.rmqverify.model.jsonl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.HistoryHeader;
import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.testkit.ManualClock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JsonlHistoryReaderTest {

  private static final String HEADER =
      "{\"kind\":\"header\",\"schemaVersion\":1,\"runId\":\"r\",\"queues\":{\"q\":\"QUORUM\"},\"attributes\":{}}";
  private static final String MSG =
      "{\"runId\":\"r\",\"publisher\":\"p\",\"queue\":\"q\",\"seq\":0,\"uuid\":\"u\"}";
  private static final String PUBLISH =
      "{\"kind\":\"op\",\"id\":0,\"process\":\"p\",\"type\":\"INVOKE\",\"f\":\"PUBLISH\",\"t\":1,"
          + "\"wall\":\"2026-01-01T00:00:00Z\",\"error\":\"\",\"value\":{\"type\":\"publication\",\"msg\":"
          + MSG
          + "}}";

  @Test
  void readsAMinimalHistory() throws IOException {
    History h = read(HEADER + "\n\n" + PUBLISH + "\r\n");
    assertThat(h.size()).isEqualTo(1);
    assertThat(h.header().queues()).containsEntry("q", QueueKind.QUORUM);
  }

  @Test
  void emptyInputHasNoHeader() {
    assertThatThrownBy(() -> read(""))
        .isInstanceOf(HistoryFormatException.class)
        .hasMessage("empty input: the header line is missing")
        .extracting(e -> ((HistoryFormatException) e).line())
        .isEqualTo(0L);
    assertThatThrownBy(() -> read("\n  \n")).hasMessageContaining("header line is missing");
  }

  @Test
  void lineLengthIsBounded() throws IOException {
    JsonlHistoryReader reader = new JsonlHistoryReader(HEADER.length(), 10);
    assertThat(reader.read(stream(HEADER)).size()).isZero();
    JsonlHistoryReader tight = new JsonlHistoryReader(HEADER.length() - 1, 10);
    assertThatThrownBy(() -> tight.read(stream(HEADER)))
        .hasMessage("line 1: line longer than " + (HEADER.length() - 1) + " characters")
        .extracting(e -> ((HistoryFormatException) e).line())
        .isEqualTo(1L);
  }

  @Test
  void operationCountIsBounded() throws IOException {
    String second =
        PUBLISH
            .replace("\"id\":0", "\"id\":1")
            .replace("\"uuid\":\"u\"", "\"uuid\":\"v\"")
            .replace("\"seq\":0", "\"seq\":1");
    assertThat(
            new JsonlHistoryReader(4096, 2)
                .read(stream(HEADER + "\n" + PUBLISH + "\n" + second))
                .size())
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                new JsonlHistoryReader(4096, 1)
                    .read(stream(HEADER + "\n" + PUBLISH + "\n" + second)))
        .hasMessage("line 3: more than 1 operations");
  }

  @Test
  void limitsMustBePositive() {
    assertThatThrownBy(() -> new JsonlHistoryReader(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JsonlHistoryReader(1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "[1]|expected a JSON object",
        "{} {}|trailing content",
        "{\"a\":|invalid JSON",
        "{\"a\":1.5}|unsupported JSON token VALUE_NUMBER_FLOAT",
        "{\"a\":null}|unsupported JSON token VALUE_NULL",
        "{\"a\":1,\"a\":2}|invalid JSON",
        "{\"a\":[[[[[[[1]]]]]]]}|invalid JSON",
        "{\"a\":123456789012345678901}|invalid JSON",
        "{\"a\":99999999999999999999}|integer out of range",
      })
  void rejectsMalformedJson(String line, String message) {
    assertThatThrownBy(() -> read(line))
        .isInstanceOf(HistoryFormatException.class)
        .hasMessageContaining(message);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "\"kind\":\"header\"|\"kind\":\"op\"|expected kind 'header' but got 'op'",
        "\"schemaVersion\":1|\"schemaVersion\":2|unsupported schemaVersion 2",
        "\"schemaVersion\":1|\"schemaVersion\":\"1\"|field 'schemaVersion' must be an integer",
        "\"QUORUM\"|\"FIFO\"|unknown QueueKind 'FIFO'",
        "\"attributes\":{}|\"attributes\":{},\"extra\":1|unknown field 'extra'",
        "\"attributes\":{}|\"attributes\":[]|field 'attributes' must be an object",
        "\"attributes\":{}|\"attributes\":{\"k\":1}|field 'k' must be a string",
        "\"runId\":\"r\"|\"runId\":\" \"|runId must not be blank",
        ",\"attributes\":{}|''|missing field 'attributes'",
      })
  void rejectsInvalidHeaders(String target, String replacement, String message) {
    String header = HEADER.replace(target, replacement.equals("''") ? "" : replacement);
    assertThatThrownBy(() -> read(header)).hasMessageContaining(message);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "\"kind\":\"op\"|\"kind\":\"header\"|expected kind 'op'",
        "\"type\":\"INVOKE\"|\"type\":\"MAYBE\"|unknown OpType 'MAYBE'",
        "\"f\":\"PUBLISH\"|\"f\":\"SEND\"|unknown OpFunction 'SEND'",
        "\"wall\":\"2026-01-01T00:00:00Z\"|\"wall\":\"yesterday\"|invalid instant 'yesterday'",
        "\"type\":\"publication\"|\"type\":\"telegram\"|unknown value type 'telegram'",
        "\"error\":\"\"|\"error\":\"\",\"extra\":true|unknown field 'extra'",
        "\"uuid\":\"u\"}|\"uuid\":\"u\",\"x\":1}|unknown field 'x'",
        "\"process\":\"p\"|\"process\":\"\"|process must not be blank",
        "\"seq\":0|\"seq\":-1|sequence must be >= 0",
        "\"f\":\"PUBLISH\"|\"f\":\"DRAIN\"|DRAIN cannot carry Publication",
        "\"id\":0|\"id\":true|field 'id' must be an integer",
        "\"msg\":|\"msg\":1,\"m\":|unknown field 'm'",
        "\"type\":\"INVOKE\",\"f\":\"PUBLISH\"|\"type\":\"OK\",\"f\":\"CONFIRM\"|invalid history",
      })
  void rejectsInvalidOperations(String target, String replacement, String message) {
    String op = PUBLISH.replace(target, replacement);
    assertThatThrownBy(() -> read(HEADER + "\n" + op)).hasMessageContaining(message);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "{\"type\":\"fault\",\"index\":3000000000,\"nemesis\":\"n\",\"targets\":[],\"detail\":\"\"}|does not fit an int",
        "{\"type\":\"fault\",\"index\":-3000000000,\"nemesis\":\"n\",\"targets\":[],\"detail\":\"\"}|does not fit an int",
        "{\"type\":\"fault\",\"index\":0,\"nemesis\":\"n\",\"targets\":[1],\"detail\":\"\"}|must contain strings only",
        "{\"type\":\"fault\",\"index\":0,\"nemesis\":\"n\",\"targets\":\"a\",\"detail\":\"\"}|must be an array",
        "{\"type\":\"fault\",\"index\":-1,\"nemesis\":\"n\",\"targets\":[],\"detail\":\"\"}|index must be >= 0",
        "{\"type\":\"probe\",\"round\":0,\"observer\":\"o\",\"subject\":\"s\",\"leader\":\"\",\"running\":[],\"partitions\":[],\"z\":1}|unknown field 'z'",
        "{\"type\":\"drain\",\"queue\":\"q\",\"delivered\":0,\"z\":1}|unknown field 'z'",
        "{\"type\":\"corrupt\",\"queue\":\"q\",\"digest\":\"d\",\"redelivered\":\"no\",\"tag\":0}|must be a boolean",
        "{\"type\":\"delivery\",\"msg\":"
            + "{}"
            + ",\"redelivered\":true,\"tag\":0}|missing field 'runId'",
        "{\"type\":\"delivery\",\"redelivered\":true,\"tag\":0,\"x\":0}|unknown field 'x'",
        "{\"type\":\"publication\",\"msg\":{},\"x\":0}|unknown field 'x'",
        "{\"type\":\"fault\",\"index\":0,\"nemesis\":\"n\",\"targets\":[],\"detail\":\"\",\"x\":0}|unknown field 'x'",
      })
  void rejectsInvalidValues(String value, String message) {
    String op =
        "{\"kind\":\"op\",\"id\":0,\"process\":\"n\",\"type\":\"OK\",\"f\":\"NEMESIS_START\",\"t\":1,"
            + "\"wall\":\"2026-01-01T00:00:00Z\",\"error\":\"\",\"value\":"
            + value
            + "}";
    assertThatThrownBy(() -> read(HEADER + "\n" + op)).hasMessageContaining(message);
  }

  @Test
  void rejectsInvalidUtf8() {
    byte[] bytes = (HEADER + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] corrupted = new byte[bytes.length + 2];
    System.arraycopy(bytes, 0, corrupted, 0, bytes.length);
    corrupted[bytes.length] = (byte) 0xC3;
    corrupted[bytes.length + 1] = (byte) 0x28;
    assertThatThrownBy(() -> new JsonlHistoryReader().read(new ByteArrayInputStream(corrupted)))
        .isInstanceOf(MalformedInputException.class);
  }

  @Test
  void roundTripsThroughFilesWithAndWithoutCompression(@TempDir Path dir) throws IOException {
    HistoryHeader header =
        HistoryHeader.of(
            "r",
            Map.of("q0", QueueKind.QUORUM, "q1", QueueKind.STREAM, "q2", QueueKind.CLASSIC),
            Map.of());
    for (String name : List.of("history.jsonl", "history.jsonl.gz")) {
      Path file = dir.resolve(name);
      History written;
      try (JsonlHistoryWriter writer = JsonlHistoryWriter.open(file, header)) {
        HistoryRecorder recorder = new HistoryRecorder(header, new ManualClock(), writer);
        written =
            JsonlRoundTripProperties.interpret(
                recorder, java.util.stream.IntStream.range(0, 600).boxed().toList());
      }
      assertThat(new JsonlHistoryReader().read(file)).isEqualTo(written);
      byte[] raw = Files.readAllBytes(file);
      assertThat(raw[0] == (byte) 0x1f).isEqualTo(name.endsWith(".gz"));
    }
  }

  @Test
  void openFailsOnMissingDirectory(@TempDir Path dir) {
    HistoryHeader header = HistoryHeader.of("r", Map.of(), Map.of());
    assertThatThrownBy(() -> JsonlHistoryWriter.open(dir.resolve("missing/h.jsonl"), header))
        .isInstanceOf(IOException.class);
  }

  private static History read(String content) throws IOException {
    return new JsonlHistoryReader().read(stream(content));
  }

  private static ByteArrayInputStream stream(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }
}
