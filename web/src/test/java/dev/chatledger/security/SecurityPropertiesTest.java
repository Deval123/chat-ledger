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
                new SecurityProperties.Hashing(1, ok), new SecurityProperties.Limits("1/1m", "1/1m", "1/1m", "1/1m", "1/1m", "1/1h", "1/1m", "1/1m"),
                new SecurityProperties.Size(org.springframework.util.unit.DataSize.ofKilobytes(4), org.springframework.util.unit.DataSize.ofKilobytes(4), org.springframework.util.unit.DataSize.ofKilobytes(4)), Duration.ZERO, 0, SecurityProperties.Hsts.IF_SECURE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hsts_is_if_secure_by_default_always_on_request_and_never_missing() {
        java.util.function.Function<java.util.Map<String, String>, SecurityProperties> bind = props ->
                new org.springframework.boot.context.properties.bind.Binder(
                        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(props))
                        .bindOrCreate("app.security", SecurityProperties.class);
        assertThat(bind.apply(java.util.Map.of()).hsts()).isEqualTo(SecurityProperties.Hsts.IF_SECURE);
        assertThat(bind.apply(java.util.Map.of("app.security.hsts", "always")).hsts()).isEqualTo(SecurityProperties.Hsts.ALWAYS);
        assertThatThrownBy(() -> bind.apply(java.util.Map.of("app.security.hsts", "never"))).isInstanceOf(RuntimeException.class);
        Duration ok = Duration.ofMinutes(1);
        assertThatThrownBy(() -> new SecurityProperties(new SecurityProperties.Session(ok, ok, ok),
                new SecurityProperties.Hashing(1, ok), new SecurityProperties.Limits("1/1m", "1/1m", "1/1m", "1/1m", "1/1m", "1/1h", "1/1m", "1/1m"),
                new SecurityProperties.Size(org.springframework.util.unit.DataSize.ofKilobytes(4), org.springframework.util.unit.DataSize.ofKilobytes(4),
                        org.springframework.util.unit.DataSize.ofKilobytes(4)), ok, 0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("app.security.hsts");
    }

    @Test
    void the_board_limits_have_the_approved_defaults() {
        var binder = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of()));
        SecurityProperties.Limits l = binder.bindOrCreate("app.security", SecurityProperties.class).limits();
        assertThat(l.rematchPerMinute()).isEqualTo("3/1m");
        assertThat(l.rematchPerHour()).isEqualTo("20/1h");
        assertThat(l.exportPerTenant()).isEqualTo("6/10m");
        assertThat(l.actionsPerUser()).isEqualTo("120/1m");
        assertThat(l.ingestionPerTenant()).isEqualTo("60/1m/30");
    }

    @Test
    void a_bad_board_limit_stops_startup() {
        for (String bad : new String[]{"0/1m", "-1/1m", "3", "3/0m", "three/1m", "3/1x", "", "3/1m/0"}) {
            assertThatThrownBy(() -> new SecurityProperties.Limits("1/1m", "1/1m", "1/1m", "1/1m", bad, "1/1h", "1/1m", "1/1m")).as("rematch minute " + bad)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SecurityProperties.Limits("1/1m", "1/1m", "1/1m", "1/1m", "1/1m", bad, "1/1m", "1/1m")).as("rematch hour " + bad)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SecurityProperties.Limits("1/1m", "1/1m", "1/1m", "1/1m", "1/1m", "1/1h", bad, "1/1m")).as("export " + bad)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SecurityProperties.Limits("1/1m", "1/1m", "1/1m", "1/1m", "1/1m", "1/1h", "1/1m", bad)).as("actions " + bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
