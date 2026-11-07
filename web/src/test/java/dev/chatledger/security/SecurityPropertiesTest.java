package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bad security settings stop the application instead of weakening a defense silently. Task 3.4. */
class SecurityPropertiesTest {

    @Test
    void defaults_are_thirty_minutes_idle_and_twelve_hours_absolute() {
        SecurityProperties.Session s = new SecurityProperties.Session(Duration.ofMinutes(30), Duration.ofHours(12),
                Duration.ofMinutes(10));
        assertThat(s.idleTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(s.absoluteTimeout()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void zero_negative_or_inconsistent_values_are_rejected() {
        Duration ok = Duration.ofMinutes(5);
        assertThatThrownBy(() -> new SecurityProperties.Session(Duration.ZERO, ok, ok))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityProperties.Session(ok, Duration.ofSeconds(-1), ok))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityProperties.Session(Duration.ofHours(2), Duration.ofHours(1), ok))
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> new SecurityProperties.Hashing(0, ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityProperties.Hashing(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityProperties(new SecurityProperties.Session(ok, ok, ok),
                new SecurityProperties.Hashing(1, ok), new SecurityProperties.Limits("1/1m", "1/1m", "1/1m", "1/1m"),
                new SecurityProperties.Size(org.springframework.util.unit.DataSize.ofKilobytes(4), org.springframework.util.unit.DataSize.ofKilobytes(4), org.springframework.util.unit.DataSize.ofKilobytes(4)), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
