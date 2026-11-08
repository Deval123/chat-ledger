package dev.chatledger.matching;

import dev.chatledger.matching.NameSimilarity.Agreement;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Name agreement between an SMS sender and a customer: accent-insensitive, order-independent, symmetric, bounded, and never any part of a
 * name in the result. All names are invented.
 *
 * <p>Requirement: 3.1, 9.2. Task 6.1.
 */
class NameSimilarityTest {

    @Test
    void the_same_full_name_agrees_fully_whatever_the_case_accents_and_order() {
        assertThat(NameSimilarity.compare("Ana Kamga", "ana kamga")).isEqualTo(Agreement.FULL);
        assertThat(NameSimilarity.compare("Kamga Ana", "Ana Kamga")).isEqualTo(Agreement.FULL);
        assertThat(NameSimilarity.compare("A\u00EFcha N\u2019Diaye", "AICHA NDIAYE")).as("apostrophes are dropped, accents fold").isEqualTo(Agreement.FULL);
        assertThat(NameSimilarity.compare("Jean-Luc Picard", "Jean Luc Picard")).as("a hyphen separates").isEqualTo(Agreement.FULL);
        assertThat(NameSimilarity.compare("Jos\u00E9 Mu\u00F1oz", "jose munoz")).isEqualTo(Agreement.FULL);
    }

    @Test
    void an_initial_agrees_weakly_in_either_direction() {
        assertThat(NameSimilarity.compare("Ana K.", "Ana Kamga")).isEqualTo(Agreement.INITIAL);
        assertThat(NameSimilarity.compare("Ana Kamga", "Ana K.")).as("symmetric").isEqualTo(Agreement.INITIAL);
        assertThat(NameSimilarity.compare("K. Ana", "Ana Kamga")).isEqualTo(Agreement.INITIAL);
    }

    @Test
    void a_first_name_alone_is_a_weak_agreement_and_a_short_one_is_none() {
        assertThat(NameSimilarity.compare("Ana", "Ana Kamga")).isEqualTo(Agreement.INITIAL);
        assertThat(NameSimilarity.compare("Al", "Al Bundy")).as("two letters are not enough on their own").isEqualTo(Agreement.NONE);
    }

    @Test
    void different_names_do_not_agree() {
        assertThat(NameSimilarity.compare("Ana Kamga", "Bea Nkomo")).isEqualTo(Agreement.NONE);
        assertThat(NameSimilarity.compare("Ana K.", "Ana Nkomo")).as("the initial must match").isEqualTo(Agreement.NONE);
        assertThat(NameSimilarity.compare("Ana Kamga", "Ana Kamgang")).as("no prefix matching beyond one letter").isEqualTo(Agreement.NONE);
        assertThat(NameSimilarity.compare("Ana", "Anabel Kamga")).isEqualTo(Agreement.NONE);
    }

    @Test
    void digits_are_part_of_a_token() {
        assertThat(NameSimilarity.compare("Ana 2", "Ana 3")).isEqualTo(Agreement.NONE);
        assertThat(NameSimilarity.compare("Ana 2", "Ana 2")).isEqualTo(Agreement.FULL);
    }

    @Test
    void a_token_is_used_once() {
        assertThat(NameSimilarity.compare("Ana Ana", "Ana Kamga")).isEqualTo(Agreement.NONE);
    }

    @Test
    void the_neutral_name_and_missing_or_empty_names_never_agree() {
        assertThat(NameSimilarity.compare("Unknown customer", NameSimilarity.NEUTRAL_NAME)).isEqualTo(Agreement.NONE);
        assertThat(NameSimilarity.compare("Ana", "UNKNOWN  CUSTOMER")).isEqualTo(Agreement.NONE);
        assertThat(NameSimilarity.compare(null, "Ana Kamga")).isEqualTo(Agreement.NONE);
        assertThat(NameSimilarity.compare("Ana Kamga", null)).isEqualTo(Agreement.NONE);
        assertThat(NameSimilarity.compare("", "Ana Kamga")).isEqualTo(Agreement.NONE);
        assertThat(NameSimilarity.compare("...", "---")).isEqualTo(Agreement.NONE);
    }

    @Test
    void work_is_bounded_by_the_token_and_length_caps() {
        assertThat(NameSimilarity.tokens("a b c d e f g h")).hasSize(8);
        assertThat(NameSimilarity.tokens("a b c d e f g h i")).as("a ninth token is not read").hasSize(8);
        assertThat(NameSimilarity.tokens("ab ".repeat(100_000))).hasSize(8);
        assertThat(NameSimilarity.tokens("a".repeat(40)).get(0)).hasSize(40);
        assertThat(NameSimilarity.tokens("a".repeat(41)).get(0)).as("a token is cut at 40 characters").hasSize(40);
        assertThat(NameSimilarity.tokens("x".repeat(100_000))).hasSize(1).first().asString().hasSize(40);
        String ninetyNine = "x".repeat(99);
        assertThat(NameSimilarity.tokens(ninetyNine + " yy")).as("only the first 100 characters are read").hasSize(1);
        assertThat(NameSimilarity.tokens(ninetyNine + " y")).hasSize(1);
        assertThat(NameSimilarity.tokens("x".repeat(98) + " yy")).as("the 100th character belongs to the second token").hasSize(2);
        assertThat(NameSimilarity.compare("ab ".repeat(100_000), "ab ".repeat(100_000))).isNotNull();
    }

    @Test
    void only_a_single_letter_is_an_initial_and_each_token_is_used_once() {
        assertThat(NameSimilarity.compare("Ana Al", "Ana Albert")).as("two letters are not an initial").isEqualTo(Agreement.NONE);
        assertThat(NameSimilarity.compare("Ana K. K.", "Ana Kamga Zed")).as("one token cannot serve two initials").isEqualTo(Agreement.NONE);
        assertThat(NameSimilarity.compare("Ana K. Z.", "Ana Kamga Zed")).isEqualTo(Agreement.INITIAL);
    }

    @Test
    void hostile_names_are_just_letters_and_digits_to_the_comparison() {
        for (String name : new String[]{"<script>alert(1)</script>", "Robert'); DROP TABLE customer;--", "=HYPERLINK(\"x\")", "%s%s%n", "${7*7}",
                "\u202Eana\u202C kamga", "ana\u0000kamga", "ana\r\nkamga", "\uD800 broken"}) {
            assertThat(NameSimilarity.compare(name, "Ana Kamga")).as(name).isNotNull();
            assertThat(NameSimilarity.compare("Ana Kamga", name)).as(name).isNotNull();
        }
        assertThat(NameSimilarity.compare("<b>Ana</b> Kamga", "Ana Kamga")).as("markup is punctuation, the words agree").isEqualTo(Agreement.FULL);
    }

    @Property
    boolean the_comparison_never_throws_and_is_symmetric(@ForAll("names") String a, @ForAll("names") String b) {
        // None of the generated names is the neutral name, which is the one deliberate asymmetry.
        return NameSimilarity.compare(a, b) == NameSimilarity.compare(b, a);
    }

    @Provide
    Arbitrary<String> names() {
        Arbitrary<String> words = Arbitraries.of("ana", "Ana", "K.", "kamga", "Kamga", "N'Diaye", "al", "b", "Z\u00E9", "mu\u00F1oz", "<b>", "42", "-", ".");
        return words.list().ofMinSize(0).ofMaxSize(10).map(l -> String.join(" ", l));
    }
}
