package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Timeouts and quick effect of disabling a user or changing a role, with a clock the test moves
 * (nothing waits). The idle timeout belongs to the servlet container and is checked as a setting in
 * {@link SessionSettingsIT}.
 *
 * <p>Requirements: 9.1, 9.4. Tasks 3.5, 3.7.
 */
class SessionLifetimeIT extends AbstractWebIT {

    private static boolean signedIn(Browser b) {
        return b.get("/app").status() == 200;
    }

    @Test
    void the_session_ends_twelve_hours_after_sign_in_however_active_it_is() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        for (int hour = 1; hour <= 11; hour++) {
            clock.advance(Duration.ofHours(1));
            assertThat(signedIn(b)).as("after " + hour + " hours of activity").isTrue();
        }
        clock.advance(Duration.ofMinutes(59));
        assertThat(signedIn(b)).as("just before 12 hours").isTrue();
        clock.advance(Duration.ofMinutes(2));
        Browser.Response r = b.get("/app");
        assertThat(r.status()).as("just after 12 hours").isEqualTo(302);
        assertThat(r.header("location")).endsWith("/login");
    }

    @Test
    void a_disabled_user_loses_the_session_within_the_recheck_interval() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser b = signedIn(w.slug(), helper.email(), helper.password());
        assertThat(signedIn(b)).isTrue();

        asOwner("UPDATE app_user SET disabled_at = now() WHERE id = ?", helper.userId());
        clock.advance(Duration.ofSeconds(30));
        assertThat(signedIn(b)).as("inside the interval the database is not asked again").isTrue();
        clock.advance(Duration.ofSeconds(31));
        assertThat(signedIn(b)).as("after the interval").isFalse();
        // And it stays ended: re-enabling the user does not bring the old session back.
        asOwner("UPDATE app_user SET disabled_at = NULL WHERE id = ?", helper.userId());
        assertThat(signedIn(b)).isFalse();
    }

    @Test
    void a_disabled_workspace_ends_every_session_in_it() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        asOwner("UPDATE tenant SET disabled_at = now() WHERE id = ?", w.tenantId());
        clock.advance(Duration.ofSeconds(61));
        assertThat(signedIn(b)).isFalse();
    }

    @Test
    void a_removed_role_takes_effect_within_the_recheck_interval() {
        Workspace w = newWorkspace();
        Member second = addUser(w, Role.SELLER);
        Browser b = signedIn(w.slug(), second.email(), second.password());
        assertThat(b.get("/admin/probe/export").status()).isEqualTo(200);

        asOwner("UPDATE app_user SET role = 'HELPER' WHERE id = ?", second.userId());
        clock.advance(Duration.ofSeconds(61));
        assertThat(b.get("/admin/probe/export").status()).as("demoted").isEqualTo(403);
        assertThat(b.get("/app/probe/whoami").body()).endsWith("HELPER");
        assertThat(signedIn(b)).as("still signed in, with fewer rights").isTrue();
    }

    @Test
    void a_granted_role_also_takes_effect() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser b = signedIn(w.slug(), helper.email(), helper.password());
        assertThat(b.get("/admin/probe/export").status()).isEqualTo(403);
        asOwner("UPDATE app_user SET role = 'SELLER' WHERE id = ?", helper.userId());
        clock.advance(Duration.ofSeconds(61));
        assertThat(b.get("/admin/probe/export").status()).isEqualTo(200);
    }

    @Test
    void another_users_session_is_not_affected_by_disabling_one_user() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser other = signedIn(w.slug(), helper.email(), helper.password());
        asOwner("UPDATE app_user SET disabled_at = now() WHERE id = ?", helper.userId());
        clock.advance(Duration.ofSeconds(61));
        assertThat(signedIn(other)).isFalse();
        assertThat(signedIn(seller)).isTrue();
    }
}
