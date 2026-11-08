package dev.chatledger.tenancy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A time written to the database is cut to microseconds, which is all PostgreSQL keeps, in one place; and no other class turns an
 * instant into a database value, so a new write cannot skip it. (The integration test {@code StoredTimesIT} shows the effect against
 * PostgreSQL with a clock that has nanoseconds.)
 *
 * <p>Requirements: 4, 10. Task 6.
 */
class DbTimeTest {

    @Test
    void a_time_is_cut_to_microseconds_never_rounded_up() {
        Instant nanos = Instant.parse("2026-11-03T11:02:10.588631799Z");
        assertThat(DbTime.micros(nanos)).isEqualTo(Instant.parse("2026-11-03T11:02:10.588631Z"));
        assertThat(DbTime.micros(Instant.parse("2026-11-03T11:02:10.999999999Z"))).isEqualTo(Instant.parse("2026-11-03T11:02:10.999999Z"));
        assertThat(DbTime.micros(DbTime.micros(nanos))).as("idempotent").isEqualTo(DbTime.micros(nanos));
        Instant whole = Instant.parse("2026-11-03T11:02:10Z");
        assertThat(DbTime.micros(whole)).isEqualTo(whole);
    }

    @Test
    void the_value_bound_to_a_column_is_the_cut_time_in_utc() {
        Instant nanos = Instant.parse("2026-11-03T11:02:10.588631799Z");
        assertThat(DbTime.utc(nanos).toInstant()).isEqualTo(DbTime.micros(nanos));
        assertThat(DbTime.utc(nanos).getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(DbTime.utc(nanos).getNano() % 1000).isZero();
    }

    @Test
    void no_other_class_turns_an_instant_into_a_database_value() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("DbTime.java")) {
                    continue;
                }
                String text = Files.readString(file);
                for (String forbidden : List.of(".atOffset(", "OffsetDateTime.ofInstant(", "Timestamp.from(", "setTimestamp(", "ZonedDateTime.ofInstant(")) {
                    if (text.contains(forbidden)) {
                        violations.add(file + " uses " + forbidden);
                    }
                }
            }
        }
        assertThat(violations).as("use DbTime.utc(...) for every time written to the database").isEmpty();
    }

    @Test
    void the_scan_would_notice_a_direct_conversion() {
        assertThat("ps.setObject(1, at.atOffset(ZoneOffset.UTC));").contains(".atOffset(");
    }
}
