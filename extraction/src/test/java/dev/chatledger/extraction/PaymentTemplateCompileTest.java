package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.chatledger.extraction.TestSpecs.GOOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every rule a template must satisfy before it can be used, and the proof that each rule refuses with a code and nothing else.
 * All content is synthetic.
 *
 * <p>Requirements: 2.2, 8. Task 5.1.
 */
class PaymentTemplateCompileTest {

    private static void refuses(PaymentTemplateSpec spec, TemplateProblem problem) {
        assertThatThrownBy(() -> PaymentTemplate.compile(spec)).as(problem.name()).isInstanceOfSatisfying(TemplateException.class,
                e -> assertThat(e.problem()).isEqualTo(problem));
    }

    private static void refusesTemplate(String template, TemplateProblem problem) {
        refuses(TestSpecs.bare(template), problem);
    }

    @Test
    void a_valid_template_compiles_and_exposes_what_it_reads() {
        PaymentTemplate t = PaymentTemplate.compile(TestSpecs.bare(GOOD));
        assertThat(t.id()).isEqualTo("t-1");
        assertThat(t.provider()).isEqualTo("PROVIDER_T");
        assertThat(t.holes()).containsExactlyInAnyOrder(HoleType.AMOUNT, HoleType.SENDER_NAME, HoleType.SENDER_PHONE,
                HoleType.TRANSACTION_ID, HoleType.PAID_AT);
    }

    @Test
    void holes_may_start_or_end_a_template() {
        assertThatCode(() -> TestSpecs.compile("{senderName}: paid {amount} XAF with id {transactionId} on {paidAt}")).doesNotThrowAnyException();
        assertThatCode(() -> TestSpecs.compile("Paid {amount} XAF by wire, id {transactionId} on {paidAt}")).doesNotThrowAnyException();
    }

    @Test
    void the_most_holes_a_template_can_have_is_within_the_cap() {
        // Each hole type may appear once, so the cap cannot be reached by construction; this fails if a type is ever added without raising it.
        assertThat(HoleType.values().length).isLessThanOrEqualTo(PaymentLimits.MAX_HOLES);
        assertThatCode(() -> TestSpecs.compile("Pay {amount} | {currency} | {senderName} | {senderPhone} | {transactionId} | {reference} | {paidAt}"))
                .doesNotThrowAnyException();
    }

    // ── Identity and field rules ───────────────────────────────────────────

    @Test
    void id_provider_and_currency_have_fixed_shapes() {
        for (String bad : new String[]{"Bad_ID", "", "UPPER", "a b", "a".repeat(65), "caf\u00E9", "../x", null}) {
            refuses(TestSpecs.with(bad, "PROVIDER_T", "XAF", 0, true, "+01:00", "dd/MM/yyyy HH:mm", GOOD), TemplateProblem.BAD_ID);
        }
        for (String bad : new String[]{"provider_t", "", "A".repeat(33), "P-T", "P T", null}) {
            refuses(TestSpecs.with("t-1", bad, "XAF", 0, true, "+01:00", "dd/MM/yyyy HH:mm", GOOD), TemplateProblem.BAD_PROVIDER);
        }
        for (String bad : new String[]{"xaf", "XA", "XAFF", "X1F", "", null, "X F"}) {
            refuses(TestSpecs.with("t-1", "PROVIDER_T", bad, 0, true, "+01:00", "dd/MM/yyyy HH:mm", GOOD), TemplateProblem.BAD_CURRENCY);
        }
        for (int bad : new int[]{-1, 4, 100}) {
            refuses(TestSpecs.with("t-1", "PROVIDER_T", "XAF", bad, true, "+01:00", "dd/MM/yyyy HH:mm", GOOD), TemplateProblem.BAD_SCALE);
        }
    }

    @Test
    void the_zone_is_a_fixed_offset_only() {
        for (String good : new String[]{"Z", "+01:00", "-05:30", "+14:00", "+00:00"}) {
            assertThatCode(() -> PaymentTemplate.compile(TestSpecs.with("t-1", "PROVIDER_T", "XAF", 0, true, good, "dd/MM/yyyy HH:mm", GOOD)))
                    .as(good).doesNotThrowAnyException();
        }
        for (String bad : new String[]{"Africa/Douala", "UTC", "+1:00", "+01", "+25:00", "+01:60", "01:00", "", null, "+01:00:00", "+0a:00"}) {
            refuses(TestSpecs.with("t-1", "PROVIDER_T", "XAF", 0, true, bad, "dd/MM/yyyy HH:mm", GOOD), TemplateProblem.BAD_ZONE);
        }
    }

    @Test
    void the_date_format_is_numeric_fixed_width_and_complete() {
        for (String good : new String[]{"dd/MM/yyyy HH:mm", "yyyy-MM-dd HH:mm:ss", "dd.MM.yyyy HH:mm", "yyyy/MM/dd HH:mm"}) {
            assertThatCode(() -> PaymentTemplate.compile(TestSpecs.with("t-1", "PROVIDER_T", "XAF", 0, true, "+01:00", good, GOOD)))
                    .as(good).doesNotThrowAnyException();
        }
        for (String bad : new String[]{"dd/MM/yy HH:mm", "dd/MM/yyyy", "dd/MM/yyyy HH:mm:ss.SSS", "dd/dd/yyyy HH:mm", "dd/MM/dd/yyyy HH:mm", "dd/MM/yyyy HH:mm:mm", "MMM dd yyyy HH:mm",
                "", "d/M/yyyy H:m", "EEE dd/MM/yyyy HH:mm", "dd/MM/yyyy hh:mm a", "dd/MM/yyyy HH:mm'Z'", "yyyy-MM-dd'T'HH:mm", null,
                "dd/MM/yyyy HH:mm ".repeat(5), "dd/MM/yyyy HH:mm\n"}) {
            refuses(TestSpecs.with("t-1", "PROVIDER_T", "XAF", 0, true, "+01:00", bad, GOOD), TemplateProblem.BAD_DATE_FORMAT);
        }
    }

    // ── Template text ──────────────────────────────────────────────────────

    @Test
    void empty_and_oversized_templates() {
        refusesTemplate("", TemplateProblem.EMPTY_TEMPLATE);
        refuses(TestSpecs.bare(null), TemplateProblem.EMPTY_TEMPLATE);
        String filler = "x".repeat(PaymentLimits.MAX_TEMPLATE_CHARS);
        refusesTemplate(filler + " {amount} {paidAt}", TemplateProblem.TEMPLATE_TOO_LONG);
        assertThatCode(() -> TestSpecs.compile("Paid " + "x".repeat(PaymentLimits.MAX_TEMPLATE_CHARS - 50) + " {amount} at {paidAt}"))
                .doesNotThrowAnyException();
    }

    @Test
    void the_template_length_cap_is_exact() {
        String head = "Paid {amount} at {paidAt}";
        String atCap = head + "x".repeat(PaymentLimits.MAX_TEMPLATE_CHARS - head.length());
        assertThat(atCap).hasSize(PaymentLimits.MAX_TEMPLATE_CHARS);
        assertThatCode(() -> TestSpecs.compile(atCap)).doesNotThrowAnyException();
        refusesTemplate(atCap + "x", TemplateProblem.TEMPLATE_TOO_LONG);
    }

    @Test
    void the_literal_character_minimum_is_exact() {
        // "Paid " + " at " + " no" = 12 literal characters: allowed. One fewer: refused.
        assertThatCode(() -> TestSpecs.compile("Paid {amount} at {paidAt} no")).doesNotThrowAnyException();
        refusesTemplate("Paid {amount} at {paidAt} n", TemplateProblem.TOO_FEW_LITERALS);
    }

    @Test
    void holes_must_be_well_formed_known_and_unique() {
        refusesTemplate("Paid {amount} by {nobody}, ok on {paidAt}", TemplateProblem.UNKNOWN_HOLE);
        refusesTemplate("Paid {Amount} by wire, ok on {paidAt}", TemplateProblem.UNKNOWN_HOLE);
        refusesTemplate("Paid {} by wire, ok on {paidAt}", TemplateProblem.UNKNOWN_HOLE);
        refusesTemplate("Paid {amount by wire, ok on {paidAt}", TemplateProblem.UNTERMINATED_HOLE);
        refusesTemplate("Paid {amount", TemplateProblem.UNTERMINATED_HOLE);
        refusesTemplate("Paid {amountamountamountamountamount} by wire {paidAt}", TemplateProblem.UNTERMINATED_HOLE);
        refusesTemplate("Paid amount} by wire, ok on {paidAt}", TemplateProblem.STRAY_BRACE);
        refusesTemplate("Paid {amount} twice {amount} on {paidAt}", TemplateProblem.DUPLICATE_HOLE);
        refusesTemplate("Paid {amount}{currency} by wire, ok on {paidAt}", TemplateProblem.ADJACENT_HOLES);
        refusesTemplate("{amount}{paidAt} paid by wire to Ana", TemplateProblem.ADJACENT_HOLES);
    }

    @Test
    void a_template_needs_enough_fixed_wording_to_not_be_a_match_everything_rule() {
        refusesTemplate("{amount} {paidAt}", TemplateProblem.TOO_FEW_LITERALS);
        refusesTemplate("Paid {amount} at {paidAt}", TemplateProblem.TOO_FEW_LITERALS);            // 9 literal characters
        assertThatCode(() -> TestSpecs.compile("Paid {amount} XAF at {paidAt}")).doesNotThrowAnyException();   // 12
    }

    @Test
    void literal_text_must_already_be_in_normalised_form() {
        refusesTemplate("Paid  {amount} XAF at {paidAt}", TemplateProblem.NOT_NORMALISED);          // two spaces
        refusesTemplate("Paid {amount} XAF at {paidAt} ", TemplateProblem.NOT_NORMALISED);          // trailing space
        refusesTemplate(" Paid {amount} XAF at {paidAt}", TemplateProblem.NOT_NORMALISED);          // leading space
        refusesTemplate("Paid\t{amount} XAF at {paidAt}", TemplateProblem.NOT_NORMALISED);          // tab
        refusesTemplate("Paid {amount} XAF at\r\n{paidAt}", TemplateProblem.NOT_NORMALISED);        // CRLF
        refusesTemplate("Paid {amount} XAF\u00A0at {paidAt}", TemplateProblem.NOT_NORMALISED);      // no-break space
        refusesTemplate("Paid {amount} XAF at\u200B {paidAt}", TemplateProblem.NOT_NORMALISED);     // zero-width character
        refusesTemplate("Re\u00E7u: {amount} XAF at {paidAt}".replace("\u00E7", "c\u0327"), TemplateProblem.NOT_NORMALISED);   // decomposed accent
        assertThatCode(() -> TestSpecs.compile("Re\u00E7u: {amount} XAF at {paidAt}")).doesNotThrowAnyException();
        assertThatCode(() -> TestSpecs.compile("Paid {amount} XAF\nat {paidAt}")).as("one line break is normal").doesNotThrowAnyException();
    }

    @Test
    void the_delimiter_rule_what_follows_a_hole_must_stop_it() {
        // senderName allows letters and spaces: a following literal that starts with a letter would be swallowed.
        refusesTemplate("Paid {amount} XAF by {senderName} ending {senderLast4} at {paidAt}", TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);
        refusesTemplate("Paid {amount} XAF by {senderName}. Done on {paidAt}", TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);   // name allows '.'
        refusesTemplate("Paid {amount} XAF by {senderName}-bank on {paidAt}", TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);   // name allows '-'
        // transactionId allows letters, digits, '_' and '-': a following literal starting with one of them is not a delimiter.
        refusesTemplate("Paid {amount} XAF id {transactionId}x on {paidAt}", TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);
        refusesTemplate("Paid {amount} XAF id {transactionId}-1 on {paidAt}", TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);
        // amount allows digits and separators: ".00" would be eaten.
        refusesTemplate("Paid {amount}.00 XAF by wire on {paidAt}", TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);
        // Two holes with only a space between them must not share characters.
        refusesTemplate("Paid {senderName} {reference} XAF by wire on {paidAt}", TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);
        // These are fine.
        assertThatCode(() -> TestSpecs.compile("Paid {amount} {currency} by {senderName} ({senderPhone}). Txn {transactionId}. {paidAt}"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TestSpecs.compile("Paid {amount} XAF by {senderName}, id {transactionId} on {paidAt}")).doesNotThrowAnyException();
    }

    // ── Which holes a template needs ───────────────────────────────────────

    @Test
    void amount_and_time_are_always_required() {
        refusesTemplate("Paid by wire to Ana, id {transactionId} on {paidAt}", TemplateProblem.MISSING_REQUIRED_HOLE);
        refusesTemplate("Paid {amount} XAF by wire to Ana, id {transactionId}", TemplateProblem.MISSING_REQUIRED_HOLE);
        refusesTemplate("Paid by wire to Ana, and that is all there is", TemplateProblem.MISSING_REQUIRED_HOLE);
    }

    @Test
    void the_transaction_id_hole_matches_what_the_template_declares() {
        refuses(TestSpecs.with("t-1", "PROVIDER_T", "XAF", 0, true, "+01:00", "dd/MM/yyyy HH:mm",
                "Paid {amount} XAF by wire to Ana on {paidAt}"), TemplateProblem.MISSING_REQUIRED_HOLE);
        refuses(TestSpecs.with("t-1", "PROVIDER_T", "XAF", 0, false, "+01:00", "dd/MM/yyyy HH:mm",
                "Paid {amount} XAF by wire to Ana, id {transactionId} on {paidAt}"), TemplateProblem.TRANSACTION_ID_NOT_ALLOWED);
        assertThatCode(() -> PaymentTemplate.compile(TestSpecs.with("t-1", "PROVIDER_T", "XAF", 0, false, "+01:00", "dd/MM/yyyy HH:mm",
                "Paid {amount} XAF by wire to Ana on {paidAt}"))).doesNotThrowAnyException();
    }

    @Test
    void a_full_phone_and_a_last_four_cannot_both_be_read() {
        refusesTemplate("Paid {amount} XAF by {senderName} ({senderPhone}) ({senderLast4}), id {transactionId} on {paidAt}",
                TemplateProblem.SENDER_CONFLICT);
    }

    @Test
    void a_refusal_names_the_template_and_the_problem_and_never_the_template_text() {
        String secret = "Paid {amount} XAF by wire to Ana K. SECRETWORDING and {nobody} on {paidAt}";
        assertThatThrownBy(() -> TestSpecs.compile(secret)).isInstanceOfSatisfying(TemplateException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("template t-1: UNKNOWN_HOLE");
            assertThat(e.getMessage()).doesNotContain("SECRETWORDING");
            assertThat(e.getCause()).isNull();
        });
        assertThatThrownBy(() -> PaymentTemplate.compile(TestSpecs.with("Bad Id With <script>", "PROVIDER_T", "XAF", 0, true, "+01:00",
                "dd/MM/yyyy HH:mm", GOOD))).hasMessage("template (invalid id): BAD_ID");
        assertThat(TestSpecs.good().toString()).isEqualTo("PaymentTemplateSpec[good]");
    }

    @Test
    void examples_lists_are_copied() {
        var examples = new java.util.ArrayList<>(TestSpecs.good().examples());
        var spec = new PaymentTemplateSpec("x", "P", "XAF", 0, true, "Z", "dd/MM/yyyy HH:mm", GOOD, examples, List.of());
        examples.clear();
        assertThat(spec.examples()).hasSize(3);
    }
}
