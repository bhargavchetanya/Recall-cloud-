package dev.recall;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests move by hand. */
public final class ManualClock extends Clock {

    public Instant now = Instant.parse("2026-09-24T12:00:00Z");

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
