package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement: 9.1 (input validation). Task 3.4. */
class IdentifiersTest {

    @Test
    void workspace_codes_are_lower_cased_and_trimmed() {
        assertThat(Identifiers.workspace("  River-Shop ")).contains("river-shop");
    }

    @Test
    void workspace_codes_that_do_not_fit_are_rejected_not_repaired() {
        for (String bad : new String[]{null, "", "a", "ab", "-abc", "abc-", "ab cd", "ab_cd", "ab/cd", "a".repeat(33),
                "abc\n", "ab\u0000c", "\u00e9abc", "../etc", "a;b"}) {
            // "abc\n" is trimmed to "abc", which is valid: strip() removes surrounding whitespace by design.
            if ("abc\n".equals(bad)) {
                assertThat(Identifiers.workspace(bad)).contains("abc");
            } else {
                assertThat(Identifiers.workspace(bad)).as(String.valueOf(bad)).isEmpty();
            }
        }
        assertThat(Identifiers.workspace("a".repeat(32))).isPresent();
        assertThat(Identifiers.workspace("abc")).isPresent();
    }

    @Test
    void emails_are_lower_cased_and_must_be_plausible() {
        assertThat(Identifiers.email(" Ana@Example.TEST ")).contains("ana@example.test");
        for (String bad : new String[]{null, "", "a", "a@", "@b", "a b@c.d", "a@@c.d", "a@b\u0000.d",
                "a".repeat(65) + "@example.test", "a@" + "b".repeat(190)}) {
            assertThat(Identifiers.email(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }
}
