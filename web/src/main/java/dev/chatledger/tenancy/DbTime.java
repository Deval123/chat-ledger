package dev.chatledger.tenancy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The one place an {@link Instant} becomes a value for a {@code timestamptz} column. PostgreSQL keeps microseconds; a Linux clock has
 * nanoseconds. A time that is stored as it is comes back different from the one the service returned, on some machines and not on
 * others. So every time written to the database is first cut to microseconds, and a service that reports a time cuts it the same way
 * ({@link #micros}) before it uses it for anything: the value stored, the value returned and the value read back are then identical
 * everywhere. A source test fails if any other class turns an instant into a database value.
 */
public final class DbTime {

    private DbTime() {
    }

    /** The instant cut to microseconds (truncated, never rounded, so that it is never later than the clock said). */
    public static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    /** The value to bind to a {@code timestamptz} parameter, already cut to microseconds. */
    public static OffsetDateTime utc(Instant instant) {
        return micros(instant).atOffset(ZoneOffset.UTC);
    }
}
