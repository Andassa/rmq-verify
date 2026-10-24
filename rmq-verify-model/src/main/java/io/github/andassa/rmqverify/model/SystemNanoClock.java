package io.github.andassa.rmqverify.model;

import java.time.Instant;

/** Stateless system clock. */
enum SystemNanoClock implements NanoClock {
  INSTANCE;

  @Override
  public long nanoTime() {
    return System.nanoTime();
  }

  @Override
  public Instant wallClock() {
    return Instant.now();
  }
}
