package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical phone form. One function is used for the hash, the last four digits and (tasks 5 and 6) finding a
 * payer from an SMS. Numbers are synthetic.
 *
 * <p>Requirement: 1.4, 9 (phone numbers stored hashed plus last 4). Task 4.
 */
class PhoneNumbersTest {

    private static String canonical(String raw) {
        return PhoneNumbers.canonicalDigits(raw).orElseThrow(() -> new AssertionError("refused: " + raw.length() + " chars"));
    }

    @Test
    void several_spellings_of_one_number_give_the_same_canonical_digits() {
        String expected = "9990000001234";
        for (String spelling : new String[]{
                "+999 000 000 1234", "+9990000001234", "009990000001234", "00 999 000 000 1234", "00-999-000-000-1234",
                "+999.000.000.1234", "+(999) 000-000-1234", "  +999 000 000 1234  ", "999 000 000 1234", "9990000001234"}) {
            assertThat(canonical(spelling)).as(spelling).isEqualTo(expected);
        }
    }

    @Test
    void the_leading_plus_or_leading_double_zero_is_removed_and_nothing_else() {
        assertThat(canonical("+0012345678")).as("only the plus goes").isEqualTo("0012345678");
        assertThat(canonical("0012345678")).as("only the double zero goes").isEqualTo("12345678");
        assertThat(canonical("0612345678")).as("a single national zero is kept: no country logic").isEqualTo("0612345678");
    }

    @Test
    void the_limit_the_same_number_with_and_without_a_country_code_is_two_numbers() {
        String withCountry = canonical("+999 612 345 678");
        String without = canonical("612 345 678");
        assertThat(withCountry).isNotEqualTo(without);
        byte[] key = "k".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(Phone.of("+999 612 345 678").orElseThrow().keyedHash(key))
                .isNotEqualTo(Phone.of("612 345 678").orElseThrow().keyedHash(key));
    }

    @Test
    void things_that_are_not_phone_numbers_are_refused() {
        for (String bad : new String[]{null, "", "   ", "abc", "call me", "12345", "1234567890123456", "+", "++999 000 000 1234",
                "999+000 000 1234", "+999 000 000 1234 ext 5", "999\u0000000 000 1234", "\u0669\u0669\u0669 000 000 1234",
                "x".repeat(41), "9".repeat(41), "12-34-5"}) {
            assertThat(PhoneNumbers.canonicalDigits(bad)).as(String.valueOf(bad)).isEmpty();
        }
        assertThat(PhoneNumbers.canonicalDigits("1234567")).as("seven digits is the minimum").isPresent();
        assertThat(PhoneNumbers.canonicalDigits("123456789012345")).as("fifteen digits is the maximum").isPresent();
        assertThat(PhoneNumbers.canonicalDigits("123456")).isEmpty();
        assertThat(PhoneNumbers.canonicalDigits("1234567890123456")).isEmpty();
    }

    @Test
    void the_hash_and_the_last_four_digits_both_come_from_the_canonical_form() {
        Phone a = Phone.of("+999 000 000 1234").orElseThrow();
        Phone b = Phone.of("00 999-000-000-1234").orElseThrow();
        byte[] key = UUID.randomUUID().toString().repeat(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(a).isEqualTo(b);
        assertThat(a.last4()).isEqualTo("1234").isEqualTo(b.last4());
        assertThat(a.keyedHash(key)).isEqualTo(b.keyedHash(key)).matches("[0-9a-f]{64}");
        assertThat(a.last4()).isEqualTo(canonical("+999 000 000 1234").substring(9));
    }

    @Test
    void the_hash_depends_on_the_key_and_on_every_digit() {
        Phone a = Phone.of("+999 000 000 1234").orElseThrow();
        Phone other = Phone.of("+999 000 000 1235").orElseThrow();
        byte[] k1 = "1".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] k2 = "2".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(a.keyedHash(k1)).isNotEqualTo(a.keyedHash(k2)).isNotEqualTo(other.keyedHash(k1));
    }

    @Test
    void a_phone_prints_masked_and_never_reveals_its_digits() {
        Phone p = Phone.of("+999 000 000 1234").orElseThrow();
        assertThat(p.toString()).isEqualTo("Phone[****1234]").doesNotContain("999").doesNotContain("000");
        assertThat(("" + p)).doesNotContain("9990000");
    }
}
