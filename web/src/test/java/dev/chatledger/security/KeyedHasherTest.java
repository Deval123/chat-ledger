package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement: 9.4 (secrets from the environment, none by default). Task 3.4. */
class KeyedHasherTest {

    private static String secret() {
        return UUID.randomUUID() + "" + UUID.randomUUID();
    }

    @Test
    void a_missing_short_or_placeholder_secret_stops_startup() {
        assertThatThrownBy(() -> new KeyedHasher(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeyedHasher("  ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeyedHasher("short")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("short ");
        assertThatThrownBy(() -> new KeyedHasher("<replace-with-random-hex-secret>"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("placeholder");
        assertThatThrownBy(() -> new KeyedHasher("please-replace-this-with-something-long-enough"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void the_hash_is_stable_keyed_and_short() {
        KeyedHasher a = new KeyedHasher(secret());
        KeyedHasher b = new KeyedHasher(secret());
        assertThat(a.shortHash("x@example.test")).isEqualTo(a.shortHash("x@example.test")).hasSize(12)
                .matches("[0-9a-f]{12}").doesNotContain("example");
        assertThat(a.shortHash("x@example.test")).isNotEqualTo(a.shortHash("y@example.test"));
        assertThat(a.shortHash("x@example.test")).isNotEqualTo(b.shortHash("x@example.test"));
    }
}
