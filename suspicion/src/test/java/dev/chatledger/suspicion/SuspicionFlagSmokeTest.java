package dev.chatledger.suspicion;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke test for the suspicion module: verifies flag construction and state.
 *
 * <p>Requirement: 5 (Suspicion flags).
 */
class SuspicionFlagSmokeTest {

    @Test
    void open_flag_has_no_resolved_at() {
        var flag = new SuspicionFlag(
                UUID.randomUUID(), UUID.randomUUID(),
                "PAYMENT", UUID.randomUUID(),
                FlagCode.DUPLICATE_TX,
                "This transaction id was already received for your account.",
                Instant.now(), null);

        assertThat(flag.isOpen()).isTrue();
        assertThat(flag.code()).isEqualTo(FlagCode.DUPLICATE_TX);
    }

    @Test
    void resolved_flag_is_not_open() {
        var now = Instant.now();
        var flag = new SuspicionFlag(
                UUID.randomUUID(), UUID.randomUUID(),
                "PAYMENT", UUID.randomUUID(),
                FlagCode.NAME_MISMATCH,
                "Sender name 'J. Doe' does not resemble customer 'Jane Smith'.",
                now, now.plusSeconds(60));

        assertThat(flag.isOpen()).isFalse();
    }

    @Test
    void blank_reason_is_rejected() {
        assertThatThrownBy(() -> new SuspicionFlag(
                UUID.randomUUID(), UUID.randomUUID(),
                "PAYMENT", UUID.randomUUID(),
                FlagCode.TIME_ANOMALY, "  ", Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
