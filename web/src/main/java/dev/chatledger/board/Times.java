package dev.chatledger.board;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Absolute times for pages, in the tenant's time zone ("03 Nov, 11:02"). No relative times: they need script to stay true. */
@Component("times")
public final class Times {
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd MMM, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.ENGLISH);

    public String format(Instant at, ZoneId zone) {
        return at == null ? "" : SHORT.format(at.atZone(zone));
    }

    public String day(java.time.LocalDate day) {
        return DAY.format(day);
    }
}
