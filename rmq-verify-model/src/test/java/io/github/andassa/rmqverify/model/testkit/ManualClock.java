package io.github.andassa.rmqverify.model.testkit;

import io.github.andassa.rmqverify.model.NanoClock;
import java.time.Duration;
import java.time.Instant;

/** Clock advanced explicitly by tests. */
public final class ManualClock implements NanoClock {

  private static final Instant ORIGIN = Instant.parse("2026-01-01T00:00:00Z");

  private long nanos;

  @Override
  public long nanoTime() {
    return nanos;
  }

  @Override
  public Instant wallClock() {
    return ORIGIN.plusNanos(nanos);
  }

  public void advance(Duration duration) {
    nanos += duration.toNanos();
  }

  public void set(long value) {
    nanos = value;
  }
}
