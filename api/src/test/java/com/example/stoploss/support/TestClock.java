package com.example.stoploss.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move ("today" decides the current policy year). */
public final class TestClock extends Clock {
  public static final Instant DEFAULT = Instant.parse("2026-11-02T12:00:00Z");
  private volatile Instant now = DEFAULT;

  public void set(Instant instant) {
    now = instant;
  }

  public void reset() {
    now = DEFAULT;
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return Clock.fixed(now, zone);
  }
}
