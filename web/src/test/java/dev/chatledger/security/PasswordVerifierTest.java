package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Argon2 uses about 19 MiB per check, so the number of checks running at once is capped; extra
 * requests wait briefly and are then refused. Task 3.4.
 */
class PasswordVerifierTest {

    @Test
    void no_more_than_the_permitted_number_of_checks_run_at_once() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger highest = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        PasswordEncoder slow = new PasswordEncoder() {
            @Override public String encode(CharSequence raw) { return "{noop}x"; }
            @Override public boolean matches(CharSequence raw, String encoded) {
                int now = running.incrementAndGet();
                highest.accumulateAndGet(now, Math::max);
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                }
                return true;
            }
        };
        PasswordVerifier verifier = new PasswordVerifier(slow, 2, Duration.ofMillis(300));
        Thread[] threads = new Thread[2];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(() -> verifier.matches("a", "b"));
        }
        while (running.get() < 2) {
            Thread.sleep(5);
        }
        assertThat(verifier.availablePermits()).isZero();
        // A third request finds no free slot and is refused after the wait, without running the check.
        assertThatThrownBy(() -> verifier.matches("a", "b")).isInstanceOf(LoginCapacityException.class);
        release.countDown();
        for (Thread t : threads) {
            t.join();
        }
        assertThat(highest.get()).isEqualTo(2);
        assertThat(verifier.availablePermits()).isEqualTo(2);
    }

    @Test
    void a_slot_is_released_when_the_check_fails_and_an_unreadable_hash_is_no_match() {
        PasswordVerifier verifier = new PasswordVerifier(PasswordHashing.create(), 1, Duration.ofMillis(200));
        assertThat(verifier.matches("x", "{noop}whatever")).isFalse();
        assertThat(verifier.matches("x", "")).isFalse();
        // Right scheme, damaged encoding: Argon2 throws IllegalStateException. Must be "no match", not a 500.
        assertThat(verifier.matches("x", "{argon2id}$argon2id$v=19$m=19456,t=2,p=1$AAAA$BBBB")).isFalse();
        assertThat(verifier.matches("x", "{argon2id}garbage")).isFalse();
        assertThat(verifier.matches("x", "{argon2id}")).isFalse();
        assertThat(verifier.availablePermits()).isEqualTo(1);
    }

    @Test
    void the_dummy_hash_is_a_real_hash_and_matches_nothing_guessable() {
        PasswordVerifier verifier = new PasswordVerifier(PasswordHashing.create(), 1, Duration.ofMillis(200));
        assertThat(verifier.dummyHash()).startsWith("{argon2id}$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(verifier.matches("password", verifier.dummyHash())).isFalse();
        assertThat(verifier.matches("", verifier.dummyHash())).isFalse();
    }
}
