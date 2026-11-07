package dev.chatledger.extraction;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement: 8.1 (normalise text before parsing). Task 4. */
class TextNormalizerTest {

    @Test
    void invisible_and_direction_characters_are_removed() {
        assertThat(TextNormalizer.normalize("Total: \u202E0051\u202C")).isEqualTo("Total: 0051");
        assertThat(TextNormalizer.normalize("15\u200B00\u2060")).isEqualTo("1500");
        assertThat(TextNormalizer.normalize("\uFEFFabc\u00ADdef")).isEqualTo("abcdef");
    }

    @Test
    void digits_of_any_script_and_full_width_forms_become_ascii() {
        assertThat(TextNormalizer.normalize("\u0661\u0665\u0660\u0660")).isEqualTo("1500");
        assertThat(TextNormalizer.normalize("\u06F1\u06F5")).isEqualTo("15");
        assertThat(TextNormalizer.normalize("\uFF11\uFF15\uFF10\uFF10 \uFF58")).isEqualTo("1500 x");
    }

    @Test
    void every_kind_of_space_and_line_break_is_reduced() {
        assertThat(TextNormalizer.normalize("a\u00A0\u202F\u2003 b")).isEqualTo("a b");
        assertThat(TextNormalizer.normalize("a\r\nb\rc\u2028d\n\n\ne")).isEqualTo("a\nb\nc\nd\ne");
        assertThat(TextNormalizer.normalize("  a  \n  b  ")).isEqualTo("a\nb");
        assertThat(TextNormalizer.normalize("a\tb\u0007c\u0000d")).isEqualTo("a b c d");
        assertThat(TextNormalizer.normalize("a\uD800b")).as("a lone surrogate").isEqualTo("a b");
    }

    @Test
    void accents_and_letters_are_kept() {
        assertThat(TextNormalizer.normalize("T\u00E9l\u00E9phone  :  A\u00EFcha")).isEqualTo("T\u00E9l\u00E9phone : A\u00EFcha");
        assertThat(TextNormalizer.normalize("Te\u0301le\u0301phone")).as("composed by NFKC").isEqualTo("T\u00E9l\u00E9phone");
    }

    @Test
    void null_and_empty_give_empty() {
        assertThat(TextNormalizer.normalize(null)).isEmpty();
        assertThat(TextNormalizer.normalize("")).isEmpty();
        assertThat(TextNormalizer.normalize(" \n \t ")).isEmpty();
    }

    @Test
    void normalising_the_whole_hostile_corpus_is_stable() {
        HostileCorpus.all().forEach((name, text) -> {
            String once = TextNormalizer.normalize(text);
            assertThat(TextNormalizer.normalize(once)).as(name).isEqualTo(once);
        });
    }

    @Property(tries = 2000)
    void normalising_twice_changes_nothing(@ForAll("messy") String s) {
        String once = TextNormalizer.normalize(s);
        assertThat(TextNormalizer.normalize(once)).isEqualTo(once);
    }

    @Property(tries = 2000)
    void the_result_has_no_format_or_control_characters_other_than_line_breaks(@ForAll("messy") String s) {
        String out = TextNormalizer.normalize(s);
        out.codePoints().forEach(cp -> {
            assertThat(Character.getType(cp)).isNotEqualTo(Character.FORMAT);
            assertThat(Character.isISOControl(cp) && cp != '\n').isFalse();
            assertThat(Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER && !(cp >= '0' && cp <= '9')).isFalse();
        });
        assertThat(out).doesNotContain("\r").doesNotContain("  ").doesNotContain("\n\n").doesNotStartWith("\n");
    }

    @Provide
    Arbitrary<String> messy() {
        Arbitrary<String> pieces = Arbitraries.of("a", "Z", "1", "5", "0", " ", "  ", "\n", "\r\n", "\t", ",", ".", ":", "x", "@", "=",
                "\u202E", "\u200B", "\uFEFF", "\u00AD", "\u00A0", "\u0661", "\uFF15", "\u00E9", "e\u0301", "\u2028", "\u0000",
                "\uD83D\uDE00", "\uD800", "\u0007", "\u2066", "\u06F5");
        return pieces.list().ofMaxSize(60).map(l -> String.join("", l));
    }
}
