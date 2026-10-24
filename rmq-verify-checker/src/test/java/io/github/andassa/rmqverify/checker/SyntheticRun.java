package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Message;
import io.github.andassa.rmqverify.model.OpType;
import io.github.andassa.rmqverify.model.QueueKind;
import io.github.andassa.rmqverify.model.testkit.HistoryFixture;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Simulation of a broker that honours every guarantee under test. Its histories must be judged
 * {@code VALID} by every checker; mutating one event must make exactly the relevant checker fail.
 */
final class SyntheticRun {

  static final String QUEUE = "q0";
  static final List<String> NODES = List.of("rabbit1", "rabbit2", "rabbit3");

  final QueueKind kind;
  final List<Ev> events;

  private SyntheticRun(QueueKind kind, List<Ev> events) {
    this.kind = kind;
    this.events = List.copyOf(events);
  }

  SyntheticRun with(List<Ev> mutated) {
    return new SyntheticRun(kind, mutated);
  }

  static SyntheticRun generate(
      long seed, QueueKind kind, int publishers, int perPublisher, int consumers) {
    return new Generator(new SplittableRandom(seed), kind, publishers, perPublisher, consumers)
        .run();
  }

  History render() {
    HistoryFixture f = new HistoryFixture(Map.of(QUEUE, kind));
    for (Ev ev : events) {
      switch (ev) {
        case Ev.Pub p -> {
          f.publish(p.m());
          if (p.outcome() != null) {
            f.confirm(p.m(), p.outcome());
          }
        }
        case Ev.Del d -> f.deliver(d.consumer(), d.m(), d.redelivered(), d.tag());
        case Ev.Ack a -> f.ack(a.consumer(), a.m(), a.redelivered(), a.tag(), a.type());
        case Ev.Corrupt c -> f.deliverCorrupt(c.consumer(), c.queue(), "deadbeef", c.tag());
        case Ev.DrainStart d -> f.drainStart(d.queue());
        case Ev.DrainEnd d -> f.drainEnd(d.queue(), d.type(), 0);
        case Ev.FaultStart s -> f.faultStart(s.index(), "partition-halves", "rabbit1");
        case Ev.FaultStop s -> f.faultStop(s.index(), "partition-halves", "rabbit1");
        case Ev.Probe p ->
            f.probe(p.round(), p.observer(), QUEUE, p.leader(), p.running(), p.partitions());
        case Ev.Advance a -> f.advance(a.duration());
      }
    }
    return f.build();
  }

  private static final class Generator {
    private final SplittableRandom rng;
    private final QueueKind kind;
    private final int publishers;
    private final int consumers;
    private final int[] remaining;
    private final long[] sequence;
    private final List<Ev> events = new ArrayList<>();
    private final Deque<Message> backlog = new ArrayDeque<>();
    private final List<Message> log = new ArrayList<>();
    private final int[] cursor;
    private final Set<String> requeued = new HashSet<>();
    private long tag = 1;
    private int faultIndex;
    private boolean faultOpen;
    private boolean forceOk = true;
    private long round;

    Generator(
        SplittableRandom rng, QueueKind kind, int publishers, int perPublisher, int consumers) {
      this.rng = rng;
      this.kind = kind;
      this.publishers = publishers;
      this.consumers = consumers;
      this.remaining = new int[publishers];
      this.sequence = new long[publishers];
      this.cursor = new int[consumers];
      java.util.Arrays.fill(remaining, perPublisher);
    }

    SyntheticRun run() {
      while (java.util.Arrays.stream(remaining).anyMatch(r -> r > 0)) {
        maybeToggleFault();
        publishOne();
        if (kind.isLog()) {
          readStream();
        } else {
          consumeQueue();
        }
      }
      if (faultOpen) {
        events.add(new Ev.FaultStop(faultIndex++));
      }
      events.add(new Ev.Advance(Duration.ofSeconds(20)));
      for (String node : NODES) {
        events.add(new Ev.Probe(round, node, "rabbit1", NODES, List.of()));
      }
      drain();
      return new SyntheticRun(kind, events);
    }

    private void maybeToggleFault() {
      if (!faultOpen && rng.nextInt(12) == 0) {
        events.add(new Ev.FaultStart(faultIndex));
        faultOpen = true;
        events.add(
            new Ev.Probe(
                round, "rabbit1", "rabbit1", List.of("rabbit1"), List.of("rabbit2", "rabbit3")));
        events.add(
            new Ev.Probe(
                round, "rabbit2", "rabbit2", List.of("rabbit2", "rabbit3"), List.of("rabbit1")));
        round++;
      } else if (faultOpen && rng.nextInt(4) == 0) {
        events.add(new Ev.FaultStop(faultIndex++));
        faultOpen = false;
        forceOk = true;
      }
    }

    private void publishOne() {
      int p;
      do {
        p = rng.nextInt(publishers);
      } while (remaining[p] == 0);
      remaining[p]--;
      Message m = HistoryFixture.msg("p" + p, QUEUE, sequence[p]++);
      OpType outcome = forceOk ? OpType.OK : randomOutcome();
      forceOk = false;
      events.add(new Ev.Pub(m, outcome));
      boolean persisted = outcome == OpType.OK || (outcome != OpType.FAIL && rng.nextBoolean());
      if (persisted) {
        backlog.add(m);
        log.add(m);
      }
    }

    private OpType randomOutcome() {
      int dice = rng.nextInt(20);
      if (dice < 15) {
        return OpType.OK;
      }
      if (dice < 16) {
        return OpType.FAIL;
      }
      return dice < 19 ? OpType.INFO : null;
    }

    private void consumeQueue() {
      while (!backlog.isEmpty() && rng.nextBoolean()) {
        Message head = backlog.peekFirst();
        String consumer = "c" + rng.nextInt(consumers);
        boolean redelivered = requeued.contains(head.uuid());
        long t = tag++;
        events.add(new Ev.Del(consumer, head, redelivered, t));
        if (rng.nextInt(10) == 0) {
          events.add(new Ev.Ack(consumer, head, redelivered, t, OpType.INFO));
          requeued.add(head.uuid());
        } else {
          events.add(new Ev.Ack(consumer, head, redelivered, t, OpType.OK));
          backlog.pollFirst();
        }
      }
    }

    private void readStream() {
      for (int c = 0; c < consumers; c++) {
        while (cursor[c] < log.size() && rng.nextBoolean()) {
          events.add(new Ev.Del("c" + c, log.get(cursor[c]), false, cursor[c]));
          if (rng.nextInt(15) == 0) {
            events.add(new Ev.Del("c" + c, log.get(cursor[c]), false, cursor[c]));
          }
          cursor[c]++;
        }
      }
    }

    private void drain() {
      String drain = HistoryFixture.drainProcess(QUEUE);
      events.add(new Ev.DrainStart(QUEUE));
      if (kind.isLog()) {
        for (int offset = 0; offset < log.size(); offset++) {
          events.add(new Ev.Del(drain, log.get(offset), false, offset));
        }
      } else {
        for (Message m : backlog) {
          long t = tag++;
          boolean redelivered = requeued.contains(m.uuid());
          events.add(new Ev.Del(drain, m, redelivered, t));
          events.add(new Ev.Ack(drain, m, redelivered, t, OpType.OK));
        }
      }
      events.add(new Ev.DrainEnd(QUEUE, OpType.OK));
    }
  }
}
