package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Typed parsers for what the holes capture. Nothing captured is used raw. Requirements: 2.1, 9. Task 5.1. */
class PaymentFieldParsersTest {

    private static final PaymentTemplate T = TestSpecs.compile(TestSpecs.GOOD);

    private static FailureReason dateFailure(String value) {
        return PaymentFieldParsers.paidAt(value, T).failure();
    }

    @Test
    void a_valid_timestamp_is_read_in_the_templates_fixed_offset() {
        assertThat(PaymentFieldParsers.paidAt("03/11/2026 10:15", T).value()).isEqualTo(Instant.parse("2026-11-03T09:15:00Z"));
        assertThat(PaymentFieldParsers.paidAt("29/02/2028 23:59", T).value()).as("a leap day").isEqualTo(Instant.parse("2028-02-29T22:59:00Z"));
        PaymentTemplate utc = PaymentTemplate.compile(TestSpecs.with("u", "PROVIDER_T", "XAF", 0, true, "Z", "yyyy-MM-dd HH:mm:ss",
                TestSpecs.GOOD));
        assertThat(PaymentFieldParsers.paidAt("2026-11-03 10:15:30", utc).value()).isEqualTo(Instant.parse("2026-11-03T10:15:30Z"));
        PaymentTemplate west = PaymentTemplate.compile(TestSpecs.with("w", "PROVIDER_T", "XAF", 0, true, "-05:30", "dd/MM/yyyy HH:mm",
                TestSpecs.GOOD));
        assertThat(PaymentFieldParsers.paidAt("03/11/2026 10:15", west).value()).isEqualTo(Instant.parse("2026-11-03T15:45:00Z"));
    }

    @Test
    void dates_that_are_not_dates_are_refused_never_adjusted() {
        for (String bad : new String[]{"31/02/2026 10:15", "29/02/2027 10:15", "32/01/2026 10:15", "00/11/2026 10:15", "03/00/2026 10:15",
                "03/13/2026 10:15", "00/00/0000 00:00", "03/11/2026 24:00", "03/11/2026 10:60", "03/11/2026 25:15", "03/11/2026 10:15:30",
                "3/11/2026 10:15", "03/11/26 10:15", "03-11-2026 10:15", "03/11/2026  10:15", "03/Nov/2026 10:15", "abcdefghijklmnop",
                "", "03/11/2026 1015", "\u0660\u0663/11/2026 10:15"}) {
            assertThat(dateFailure(bad)).as(bad).isEqualTo(FailureReason.INVALID_DATE);
        }
    }

    @Test
    void a_date_failure_carries_a_code_and_no_part_of_the_input() {
        var parsed = PaymentFieldParsers.paidAt("31/02/2026 10:15", T);
        assertThat(parsed.value()).isNull();
        assertThat(parsed.toString()).doesNotContain("31/02");
    }

    @Test
    void amounts_use_the_strict_order_amount_rules() {
        assertThat(PaymentFieldParsers.amount("12 500", 0).value().minor()).isEqualTo(12_500);
        assertThat(PaymentFieldParsers.amount("12.50", 2).value().minor()).isEqualTo(1_250);
        assertThat(PaymentFieldParsers.amount("5 00", 0).failure()).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(PaymentFieldParsers.amount("9".repeat(20), 0).failure()).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        assertThat(PaymentFieldParsers.amount("0", 0).failure()).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(PaymentFieldParsers.amount("1,500", 2).failure()).as("ambiguous").isEqualTo(FailureReason.INVALID_AMOUNT);
    }

    @Test
    void currency_words_map_to_iso_codes() {
        assertThat(PaymentFieldParsers.currency("XAF").value()).isEqualTo("XAF");
        assertThat(PaymentFieldParsers.currency("FCFA").value()).isEqualTo("XAF");
        assertThat(PaymentFieldParsers.currency("fcfa").value()).isEqualTo("XAF");
        assertThat(PaymentFieldParsers.currency("EUR").value()).isEqualTo("EUR");
        assertThat(PaymentFieldParsers.currency("francs").failure()).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(PaymentFieldParsers.currency("X1F").failure()).isEqualTo(FailureReason.INVALID_AMOUNT);
    }

    @Test
    void transaction_ids_are_short_tokens() {
        assertThat(PaymentFieldParsers.transactionId("TX123456").value()).isEqualTo("TX123456");
        assertThat(PaymentFieldParsers.transactionId("TX-1_a:b.c").value()).isNotNull();
        assertThat(PaymentFieldParsers.transactionId("T".repeat(40)).value()).isNotNull();
        for (String bad : new String[]{"TX123", "T".repeat(41), "", "TX 123456", "TX12*456", "TX1';DROP", "caf\u00E9123", "../../etc"}) {
            assertThat(PaymentFieldParsers.transactionId(bad).failure()).as(bad).isEqualTo(FailureReason.INVALID_TRANSACTION_ID);
        }
    }

    @Test
    void references_follow_the_order_reference_format() {
        assertThat(PaymentFieldParsers.reference("A-1042").value()).isEqualTo("A-1042");
        assertThat(PaymentFieldParsers.reference("a.b:c_d-1").value()).isNotNull();
        assertThat(PaymentFieldParsers.reference("r".repeat(64)).value()).isNotNull();
        for (String bad : new String[]{"", "r".repeat(65), "A 1042", "A/1042", "A;1042", "caf\u00E9"}) {
            assertThat(PaymentFieldParsers.reference(bad).failure()).as(bad).isEqualTo(FailureReason.INVALID_REFERENCE);
        }
    }

    @Test
    void names_are_bounded_and_need_a_letter() {
        assertThat(PaymentFieldParsers.name("Ana K.").value()).isEqualTo("Ana K.");
        assertThat(PaymentFieldParsers.name("  Ana  ").value()).isEqualTo("Ana");
        assertThat(PaymentFieldParsers.name("A\u00EFcha N\u00F4el").value()).isNotNull();
        for (String bad : new String[]{"A", "", "A".repeat(41), "...", "- -", "'''", "Ana\u0007"}) {
            assertThat(PaymentFieldParsers.name(bad).failure()).as(bad).isEqualTo(FailureReason.INVALID_NAME);
        }
    }

    @Test
    void phones_use_the_one_canonical_function_and_print_masked() {
        Phone phone = PaymentFieldParsers.phone("+999 000 000 1234").value();
        assertThat(phone).isEqualTo(Phone.of("00 999-000-000-1234").orElseThrow());
        assertThat(phone.toString()).isEqualTo("Phone[****1234]");
        assertThat(PaymentFieldParsers.phone("12345").failure()).isEqualTo(FailureReason.INVALID_PHONE);
        assertThat(PaymentFieldParsers.phone("999-call-me").failure()).isEqualTo(FailureReason.INVALID_PHONE);
        assertThat(PaymentFieldParsers.last4("1234").value()).isEqualTo("1234");
        for (String bad : new String[]{"123", "12345", "12a4", "", "\u0661\u0662\u0663\u0664"}) {
            assertThat(PaymentFieldParsers.last4(bad).failure()).as(bad).isEqualTo(FailureReason.INVALID_PHONE);
        }
    }
}
