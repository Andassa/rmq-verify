package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpType;
import java.time.Duration;
import java.util.List;

/** Abstract event of a synthetic run, rendered into operations by {@link SyntheticRun}. */
sealed interface Ev {

  /** Publication; {@code outcome == null} leaves the confirm pending. */
  record Pub(Message m, OpType outcome) implements Ev {}

  record Del(String consumer, Message m, boolean redelivered, long tag) implements Ev {}

  record Ack(String consumer, Message m, boolean redelivered, long tag, OpType type)
      implements Ev {}

  record Corrupt(String consumer, String queue, long tag) implements Ev {}

  record DrainStart(String queue) implements Ev {}

  record DrainEnd(String queue, OpType type) implements Ev {}

  record FaultStart(int index) implements Ev {}

  record FaultStop(int index) implements Ev {}

  record Probe(
      long round, String observer, String leader, List<String> running, List<String> partitions)
      implements Ev {}

  record Advance(Duration duration) implements Ev {}
}
