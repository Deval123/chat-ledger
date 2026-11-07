package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Password storage: Argon2id with explicit parameters at OWASP's stated minimum, a fresh salt each
 * time, and the format the database check (V013) accepts.
 *
 * <p>Requirement: 9.4. Task 3.4.
 */
class PasswordHashingTest {

    /** Same expression as chk_app_user_password_hash_format in V013. */
    private static final Pattern DATABASE_FORMAT = Pattern.compile(
            "^\\{argon2id\\}\\$argon2id\\$v=19\\$m=[0-9]+,t=[0-9]+,p=[0-9]+\\$[A-Za-z0-9+/]+\\$[A-Za-z0-9+/]+$");

    private final PasswordEncoder encoder = PasswordHashing.create();

    @Test
    void the_hash_carries_the_algorithm_and_the_parameters() {
        String hash = encoder.encode("a long enough " + UUID.randomUUID());
        assertThat(hash).startsWith("{argon2id}$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(DATABASE_FORMAT.matcher(hash).matches()).isTrue();
    }

    @Test
    void parameters_are_not_below_the_owasp_minimum_for_argon2id() {
        // 19 MiB, 2 iterations, 1 lane is the lowest OWASP configuration for Argon2id.
        assertThat(PasswordHashing.MEMORY_KIB).isGreaterThanOrEqualTo(19_456);
        assertThat(PasswordHashing.ITERATIONS).isGreaterThanOrEqualTo(2);
        assertThat(PasswordHashing.PARALLELISM).isGreaterThanOrEqualTo(1);
        assertThat(PasswordHashing.SALT_BYTES).isGreaterThanOrEqualTo(16);
        assertThat(PasswordHashing.HASH_BYTES).isGreaterThanOrEqualTo(32);
    }

    @Test
    void the_right_password_matches_and_a_wrong_one_does_not() {
        String password = "pw-" + UUID.randomUUID();
        String hash = encoder.encode(password);
        assertThat(encoder.matches(password, hash)).isTrue();
        assertThat(encoder.matches(password + "x", hash)).isFalse();
        assertThat(encoder.matches("", hash)).isFalse();
    }

    @Test
    void the_hash_does_not_contain_the_password_and_the_salt_differs_each_time() {
        String password = "pw-" + UUID.randomUUID();
        String first = encoder.encode(password);
        String second = encoder.encode(password);
        assertThat(first).doesNotContain(password).isNotEqualTo(second);
    }

    @Test
    void a_stored_value_in_another_scheme_is_rejected_by_the_encoder() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> encoder.matches("x", "{noop}x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
