package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text from outside is accepted whole or not at all. Requirement: 9.1 (validate size, type, range
 * and encoding; reject, do not guess). Task 3.9.
 */
class InputRulesTest {

    private static Map<String, List<String>> form(String raw) {
        return InputRules.parseForm(raw).orElseThrow();
    }

    @Test
    void a_normal_form_is_decoded() {
        assertThat(form("a=1&b=hello+world&c=%C3%A9%F0%9F%98%80&empty=&flag"))
                .containsEntry("a", List.of("1")).containsEntry("b", List.of("hello world"))
                .containsEntry("c", List.of("\u00e9\uD83D\uDE00")).containsEntry("empty", List.of(""))
                .containsEntry("flag", List.of(""));
        assertThat(form("k=1&k=2")).containsEntry("k", List.of("1", "2"));
        assertThat(form("")).isEmpty();
        assertThat(InputRules.parseForm(null)).isPresent();
    }

    @Test
    void line_breaks_and_tabs_are_allowed_in_values() {
        assertThat(form("text=line1%0D%0Aline2%09tab")).containsEntry("text", List.of("line1\r\nline2\ttab"));
    }

    @Test
    void invalid_encodings_make_the_whole_input_invalid() {
        for (String bad : new String[]{
                "a=%FF", "a=%C3", "a=%C0%AF", "a=%ED%A0%80",           // not valid UTF-8 (lone byte, truncated, overlong, surrogate)
                "a=%00", "a=%01", "a=%1B", "a=%7F", "a=%C2%85",         // NUL and other control characters
                "a=%", "a=%4", "a=%zz", "a=100%",                       // broken percent sequences
                "%00=1", "a%0Ab=1",                                     // control characters in a name
                "a=\u00e9"}) {                                          // a raw non-ASCII byte
            assertThat(InputRules.parseForm(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void line_breaks_are_not_allowed_in_names() {
        assertThat(InputRules.parseForm("a%0Ab=1")).isEmpty();
    }

    @Test
    void json_text_must_be_strict_utf8_without_control_characters() {
        assertThat(InputRules.validText("{\"a\": \"caf\u00e9\"}\n".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(InputRules.validText(new byte[]{'{', (byte) 0xFF, '}'})).isFalse();
        assertThat(InputRules.validText(new byte[]{'{', 0, '}'})).isFalse();
        assertThat(InputRules.validText(new byte[]{'{', 0x1B, '}'})).isFalse();
        assertThat(InputRules.validText(new byte[0])).isTrue();
    }

    @Test
    void a_field_is_trimmed_bounded_and_free_of_control_characters() {
        assertThat(InputRules.field("  Ana  ", 10, false)).contains("Ana");
        assertThat(InputRules.field("", 10, false)).isEmpty();
        assertThat(InputRules.field("   ", 10, false)).isEmpty();
        assertThat(InputRules.field(null, 10, false)).isEmpty();
        assertThat(InputRules.field("x".repeat(11), 10, false)).isEmpty();
        assertThat(InputRules.field("x".repeat(10), 10, false)).isPresent();
        assertThat(InputRules.field("\uD83D\uDE00".repeat(10), 10, false)).as("counts characters, not UTF-16 units").isPresent();
        assertThat(InputRules.field("a\u0000b", 10, false)).isEmpty();
        assertThat(InputRules.field("a\nb", 10, false)).isEmpty();
        assertThat(InputRules.field("a\nb", 10, true)).contains("a\nb");
        assertThat(InputRules.field("a\u001Bb", 10, true)).isEmpty();
    }
}
