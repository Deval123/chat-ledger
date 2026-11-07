package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static dev.chatledger.extraction.TestSpecs.GOOD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scanner: what matches, what does not, and proof that it does a fixed, small amount of work whatever the text. The step
 * counts replace a timing test: they are deterministic.
 *
 * <p>Requirements: 2.2, 8. Task 5.1.
 */
class PaymentTemplateScanTest {

    private static final PaymentTemplate T = TestSpecs.compile(GOOD);
    private static final String OK = "Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15";

    @Test
    void a_message_that_fits_gives_each_hole_its_value() {
        Map<HoleType, String> v = T.match(OK).orElseThrow();
        assertThat(v).containsEntry(HoleType.AMOUNT, "5000").containsEntry(HoleType.SENDER_NAME, "Ana K.")
                .containsEntry(HoleType.SENDER_PHONE, "+999 000 000 1234").containsEntry(HoleType.TRANSACTION_ID, "TX123456")
                .containsEntry(HoleType.PAID_AT, "03/11/2026 10:15").hasSize(5);
    }

    @Test
    void trailing_spaces_inside_a_hole_go_back_to_the_literal_that_follows() {
        Map<HoleType, String> v = T.match("Received 12 500 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15").orElseThrow();
        assertThat(v.get(HoleType.AMOUNT)).isEqualTo("12 500");
        assertThat(v.get(HoleType.SENDER_NAME)).isEqualTo("Ana K.");
    }

    @Test
    void the_whole_message_must_match_nothing_before_and_nothing_after() {
        assertThat(T.match("Hello. " + OK)).isEmpty();
        assertThat(T.match(OK + " thanks")).isEmpty();
        assertThat(T.match(OK + " ")).isEmpty();
        assertThat(T.match(OK + "\n")).isEmpty();
        assertThat(T.match("\n" + OK)).isEmpty();
        assertThat(T.match(OK + "\n" + OK)).isEmpty();
        assertThat(T.match("")).isEmpty();
        assertThat(T.match("Received")).isEmpty();
        assertThat(T.match(OK)).isPresent();
    }

    @Test
    void literal_text_must_match_exactly_including_case_and_spacing() {
        assertThat(T.match(OK.replace("Received", "received"))).isEmpty();
        assertThat(T.match(OK.replace("from", "From"))).isEmpty();
        assertThat(T.match(OK.replace("Txn", "TXN"))).isEmpty();
        assertThat(T.match(OK.replace("XAF from", "XAF  from"))).as("two spaces: not normalised").isEmpty();
        assertThat(T.match(OK.replace(" (", "("))).isEmpty();
        assertThat(T.match(OK.replace("). Txn", ") Txn"))).isEmpty();
    }

    @Test
    void each_hole_enforces_its_character_set_and_its_lengths() {
        // transactionId: letters, digits, '_' and '-', 6 to 40
        assertThat(T.match(OK.replace("TX123456", "TX12345"))).as("5+2=7 is fine, 5 is not").isPresent();
        assertThat(T.match(OK.replace("TX123456", "TX123"))).as("5 characters").isEmpty();
        assertThat(T.match(OK.replace("TX123456", "T".repeat(40)))).isPresent();
        assertThat(T.match(OK.replace("TX123456", "T".repeat(41)))).isEmpty();
        assertThat(T.match(OK.replace("TX123456", "TX12*456"))).isEmpty();
        assertThat(T.match(OK.replace("TX123456", "TX12 456"))).as("space is not allowed in an id").isEmpty();
        // amount: digits and separators, at most 20
        assertThat(T.match(OK.replace("5000", "5".repeat(20)))).isPresent();
        assertThat(T.match(OK.replace("5000", "5".repeat(21)))).isEmpty();
        assertThat(T.match(OK.replace("5000", "50a0"))).isEmpty();
        assertThat(T.match(OK.replace("5000", ""))).isEmpty();
        // name: letters, space, '.', ''' and '-', 2 to 40
        assertThat(T.match(OK.replace("Ana K.", "A"))).as("one character").isEmpty();
        assertThat(T.match(OK.replace("Ana K.", "Ab"))).isPresent();
        assertThat(T.match(OK.replace("Ana K.", "A".repeat(40)))).isPresent();
        assertThat(T.match(OK.replace("Ana K.", "A".repeat(41)))).isEmpty();
        assertThat(T.match(OK.replace("Ana K.", "Ana <b>"))).isEmpty();
        assertThat(T.match(OK.replace("Ana K.", "Ana 7"))).isEmpty();
        assertThat(T.match(OK.replace("Ana K.", "A\u00EFcha N\u00F4\u00EBl"))).as("accented letters are letters").isPresent();
        // phone: digits, '+', space, '-', '.', 7 to 20
        assertThat(T.match(OK.replace("+999 000 000 1234", "123456"))).isEmpty();
        assertThat(T.match(OK.replace("+999 000 000 1234", "1234567"))).isPresent();
        assertThat(T.match(OK.replace("+999 000 000 1234", "(999) 000 000"))).isEmpty();
    }

    @Test
    void a_hole_cannot_start_with_a_space() {
        assertThat(T.match(OK.replace("from Ana", "from  Ana"))).isEmpty();
        assertThat(T.match(OK.replace("Received 5000", "Received  5000"))).isEmpty();
    }

    @Test
    void a_trailing_hole_takes_the_rest_and_nothing_may_follow() {
        assertThat(T.match(OK.replace("03/11/2026 10:15", "03/11/2026 10:15:"))).as("':' is allowed in the time hole, then the date fails later").isPresent();
        assertThat(T.match(OK.replace("03/11/2026 10:15", "03/11/2026 10:15x"))).isEmpty();
        assertThat(T.match(OK.replace("03/11/2026 10:15", "short"))).isEmpty();
        assertThat(T.match(OK.replace("03/11/2026 10:15", "1".repeat(26)))).as("longer than the hole's maximum").isEmpty();
    }

    @Test
    void the_scan_is_deterministic_and_has_no_side_effects() {
        Optional<Map<HoleType, String>> first = T.match(OK);
        assertThat(T.match(OK)).isEqualTo(first);
        assertThat(T.scan(OK).steps()).isEqualTo(T.scan(OK).steps());
        assertThat(T.scan("nope").matched()).isFalse();
    }

    // ── Work done: the structural proof that matching cannot be slow ───────

    /** The most steps a scan can ever take: every hole looks at most max characters twice, plus every literal once. */
    private static long ceiling(PaymentTemplate t) {
        long literal = 0;
        long holes = 0;
        for (PaymentTemplate.Segment s : segments(t)) {
            if (s instanceof PaymentTemplate.Literal l) {
                literal += l.text().length();
            } else {
                holes += 2L * ((PaymentTemplate.Hole) s).type().max();
            }
        }
        return literal + holes;
    }

    private static java.util.List<PaymentTemplate.Segment> segments(PaymentTemplate t) {
        try {
            var f = PaymentTemplate.class.getDeclaredField("segments");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var list = (java.util.List<PaymentTemplate.Segment>) f.get(t);
            return list;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void the_work_per_message_is_bounded_by_the_template_whatever_the_length_of_the_text() {
        long ceiling = ceiling(T);
        assertThat(ceiling).as("the ceiling is small and fixed").isLessThan(400);
        String[] shapes = {"9", " ", "A ", "9 ", "x", "-", ". ", "9,", "\u00E9", "(", ")"};
        for (int n : new int[]{10, 1_000, 100_000, 3_000_000}) {
            for (String shape : shapes) {
                String text = shape.repeat(Math.max(1, n / shape.length()));
                assertThat(T.scan(text).steps()).as("n=%d shape='%s'", n, shape).isLessThanOrEqualTo(ceiling);
                assertThat(T.scan("Received " + text).steps()).isLessThanOrEqualTo(ceiling);
                assertThat(T.scan("Received 5000 XAF from " + text).steps()).isLessThanOrEqualTo(ceiling);
                assertThat(T.scan(OK + text).steps()).isLessThanOrEqualTo(ceiling);
            }
        }
    }

    @Test
    void ten_times_the_text_costs_no_more_steps_so_work_does_not_grow_with_the_input() {
        long small = T.scan("Received " + "9".repeat(1_000)).steps();
        long large = T.scan("Received " + "9".repeat(10_000)).steps();
        assertThat(large).isEqualTo(small);
    }

    @Test
    void every_shipped_style_template_has_a_small_ceiling() {
        PaymentTemplate wide = TestSpecs.compile("{senderName}: paid {amount} XAF with id {transactionId} on {paidAt} ref {reference}");
        assertThat(ceiling(wide)).isLessThan(1_000);
        assertThat(wide.scan("A".repeat(5_000_000)).steps()).isLessThanOrEqualTo(ceiling(wide));
    }
}
