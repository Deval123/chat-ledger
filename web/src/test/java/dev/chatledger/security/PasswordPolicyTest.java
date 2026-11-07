package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import static dev.chatledger.security.PasswordPolicy.Problem.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Requirement: 9.4. Task 3.4. */
class PasswordPolicyTest {

    @Test
    void a_long_unusual_password_is_accepted_without_composition_rules() {
        assertThat(PasswordPolicy.check("purple tractor sings quietly", "ana@example.test", "river-shop")).isEmpty();
        assertThat(PasswordPolicy.check("alllowercaseletterspassphrase", null, null)).isEmpty();
    }

    @Test
    void length_is_bounded_in_both_directions() {
        assertThat(PasswordPolicy.check("a1b2c3d4e5f", null, null)).contains(TOO_SHORT);
        assertThat(PasswordPolicy.check("a1b2c3d4e5f6", null, null)).doesNotContain(TOO_SHORT);
        assertThat(PasswordPolicy.check("ab".repeat(64), null, null)).doesNotContain(TOO_LONG);
        assertThat(PasswordPolicy.check("ab".repeat(64) + "c", null, null)).contains(TOO_LONG);
        assertThat(PasswordPolicy.check(null, null, null)).contains(TOO_SHORT);
    }

    @Test
    void length_counts_characters_not_utf16_units() {
        // 12 characters outside the BMP: 24 UTF-16 units. Must count as 12, not 24.
        String twelve = "\uD83D\uDE00".repeat(12);
        assertThat(PasswordPolicy.check(twelve, null, null)).doesNotContain(TOO_SHORT, TOO_LONG);
        assertThat(PasswordPolicy.check("\uD83D\uDE00".repeat(129), null, null)).contains(TOO_LONG);
    }

    @Test
    void control_characters_are_rejected() {
        assertThat(PasswordPolicy.check("a long enough\npassword", null, null)).contains(CONTROL_CHARACTERS);
        assertThat(PasswordPolicy.check("a long enough\u0000password", null, null)).contains(CONTROL_CHARACTERS);
    }

    @Test
    void passwords_built_from_the_email_or_workspace_are_rejected() {
        assertThat(PasswordPolicy.check("xx-Ana.Seller-xx", "ana.seller@example.test", "q")).contains(CONTAINS_EMAIL_NAME);
        assertThat(PasswordPolicy.check("my river-shop key 1", "z@example.test", "river-shop")).contains(CONTAINS_WORKSPACE);
    }

    @Test
    void very_common_passwords_and_low_variety_are_rejected() {
        assertThat(PasswordPolicy.check("password1234", null, null)).contains(TOO_COMMON);
        assertThat(PasswordPolicy.check("PASSWORD1234", null, null)).contains(TOO_COMMON);
        assertThat(PasswordPolicy.check("abababababababab", null, null)).contains(LOW_VARIETY);
    }

    @Test
    void the_messages_never_contain_the_password() {
        String password = "pw1234 secret-ish";
        for (PasswordPolicy.Problem problem : PasswordPolicy.Problem.values()) {
            assertThat(problem.message()).doesNotContain(password);
        }
    }
}
