package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two message shapes, in English and in French, and every way a message is refused. All names, numbers and
 * wording are synthetic.
 *
 * <p>Requirements: 1.1, 1.5, 8.1. Task 4.1.
 */
class OrderTextParserTest {

    private static final OrderParseContext XAF = new OrderParseContext("XAF", 0);
    private static final OrderParseContext EUR = new OrderParseContext("EUR", 2);

    private static ParsedOrder ok(String text) {
        return ok(text, XAF);
    }

    private static ParsedOrder ok(String text, OrderParseContext context) {
        ExtractionResult<ParsedOrder> r = new OrderTextParser(context).parse(text);
        assertThat(r.failureReason()).as("should parse").isEmpty();
        return r.value().orElseThrow();
    }

    private static FailureReason refused(String text) {
        return refused(text, XAF);
    }

    private static FailureReason refused(String text, OrderParseContext context) {
        ExtractionResult<ParsedOrder> r = new OrderTextParser(context).parse(text);
        assertThat(r.isSuccess()).as("should be refused").isFalse();
        return r.failureReason().orElseThrow();
    }

    // ── English ─────────────────────────────────────────────────────────────

    @Test
    void english_labelled_lines() {
        ParsedOrder o = ok("Order: A-1042\nName: Ana K.\nPhone: +999 000 000 1234\n2 x tomato box @ 1500\n"
                + "1 x rice 5kg = 3000\nTotal: 6000 XAF\nDeliver: gate near the market");
        assertThat(o.reference()).isEqualTo("A-1042");
        assertThat(o.customerName()).isEqualTo("Ana K.");
        assertThat(o.phone().last4()).isEqualTo("1234");
        assertThat(o.lines()).containsExactly(new ParsedOrder.OrderLine("tomato box", 2, 1500L, null),
                new ParsedOrder.OrderLine("rice 5kg", 1, null, 3000L));
        assertThat(o.totalMinor()).isEqualTo(6000L);
        assertThat(o.currency()).isEqualTo("XAF");
        assertThat(o.deliveryNote()).isEqualTo("gate near the market");
        assertThat(OrderValidator.validate(o, XAF).verdict()).isEqualTo(ValidationResult.Verdict.OK);
    }

    @Test
    void english_one_line() {
        ParsedOrder o = ok("2 x tomato box, 1 x rice 5kg @ 3000, total 6000 XAF, from Ana K.");
        assertThat(o.lines()).hasSize(2);
        assertThat(o.lines().get(0)).isEqualTo(new ParsedOrder.OrderLine("tomato box", 2, null, null));
        assertThat(o.lines().get(1)).isEqualTo(new ParsedOrder.OrderLine("rice 5kg", 1, 3000L, null));
        assertThat(o.totalMinor()).isEqualTo(6000L);
        assertThat(o.customerName()).isEqualTo("Ana K.");
    }

    @Test
    void one_line_commas_inside_a_number_do_not_split_and_a_delivery_note_keeps_its_commas() {
        ParsedOrder o = ok("2 x tomato @ 1,500, total 3,000 XAF, deliver gate, near the market, after 5pm");
        assertThat(o.lines().get(0).unitMinor()).isEqualTo(1500L);
        assertThat(o.totalMinor()).isEqualTo(3000L);
        assertThat(o.deliveryNote()).isEqualTo("gate, near the market, after 5pm");
    }

    @Test
    void item_syntax_variants() {
        assertThat(ok("1 X rice").lines().get(0).qty()).isEqualTo(1);
        assertThat(ok("3 \u00D7 rice").lines().get(0).qty()).isEqualTo(3);
        assertThat(ok("12   x   rice   @   500").lines().get(0)).isEqualTo(new ParsedOrder.OrderLine("rice", 12, 500L, null));
        assertThat(ok("Total: 1000\n2 x rice").totalMinor()).as("order of lines does not matter").isEqualTo(1000L);
    }

    @Test
    void the_message_is_kept_exactly_as_received() {
        String raw = "  Name:  Ana  \r\n\r\n1 x  rice\u00A0 @ 500  ";
        assertThat(ok(raw).rawText()).isEqualTo(raw);
    }

    // ── French ──────────────────────────────────────────────────────────────

    @Test
    void french_labelled_lines() {
        ParsedOrder o = ok(HostileCorpus.FrenchSamples.LABELLED);
        assertThat(o.reference()).isEqualTo("B-77");
        assertThat(o.customerName()).isEqualTo("A\u00EFcha N.");
        assertThat(o.phone().last4()).isEqualTo("5678");
        assertThat(o.lines()).containsExactly(new ParsedOrder.OrderLine("bo\u00EEte de tomates", 2, 1500L, null),
                new ParsedOrder.OrderLine("riz 5kg", 1, null, 3000L));
        assertThat(o.totalMinor()).isEqualTo(6000L);
        assertThat(o.currency()).as("FCFA and F CFA are XAF").isEqualTo("XAF");
        assertThat(o.deliveryNote()).isEqualTo("portail pr\u00E8s du march\u00E9, apr\u00E8s 17h");
        assertThat(OrderValidator.validate(o, XAF).verdict()).isEqualTo(ValidationResult.Verdict.OK);
    }

    @Test
    void french_one_line() {
        ParsedOrder o = ok(HostileCorpus.FrenchSamples.ONE_LINE);
        assertThat(o.customerName()).isEqualTo("A\u00EFcha N.");
        assertThat(o.totalMinor()).isEqualTo(6000L);
        assertThat(o.currency()).isEqualTo("XAF");
        assertThat(o.lines().get(1).unitMinor()).isEqualTo(3000L);
    }

    @Test
    void french_labels_with_and_without_accents_spacing_and_case() {
        assertThat(ok("TELEPHONE: +999 000 000 1234\n1 x riz").phone()).isNotNull();
        assertThat(ok("t\u00E9l\u00E9phone : +999 000 000 1234\n1 x riz").phone()).isNotNull();
        assertThat(ok("Te\u0301le\u0301phone : +999 000 000 1234\n1 x riz").phone()).as("decomposed accents").isNotNull();
        assertThat(ok("COMMANDE: x1\n1 x riz").reference()).isEqualTo("x1");
        assertThat(ok("Nom: Aicha\n1 x riz").customerName()).isEqualTo("Aicha");
        assertThat(ok("Montant : 500 FCFA\n1 x riz").totalMinor()).isEqualTo(500L);
        assertThat(ok("Livraison: portail\n1 x riz").deliveryNote()).isEqualTo("portail");
    }

    @Test
    void all_three_spellings_of_the_franc_are_the_tenants_xaf() {
        for (String word : new String[]{"XAF", "FCFA", "F CFA", "fcfa", "f cfa", "Xaf"}) {
            ParsedOrder o = ok("1 x riz @ 500 " + word);
            assertThat(o.currency()).as(word).isEqualTo("XAF");
            assertThat(OrderValidator.validate(o, XAF).verdict()).as(word).isEqualTo(ValidationResult.Verdict.OK);
        }
        assertThat(ok("1 x riz @ 500").currency()).as("none stated").isNull();
    }

    @Test
    void another_currency_parses_but_the_validator_refuses_it_for_an_xaf_tenant() {
        ParsedOrder o = ok("1 x riz @ 500 EUR");
        assertThat(o.currency()).isEqualTo("EUR");
        assertThat(OrderValidator.validate(o, XAF)).isEqualTo(ValidationResult.reject(FailureReason.CURRENCY_MISMATCH));
        assertThat(OrderValidator.validate(ok("1 x riz @ 500 FCFA"), EUR))
                .as("FCFA is not a euro").isEqualTo(ValidationResult.reject(FailureReason.CURRENCY_MISMATCH));
    }

    // ── Currencies with decimals ───────────────────────────────────────────

    @Test
    void a_currency_with_decimals_reads_minor_units() {
        ParsedOrder o = ok("2 x tea @ 12.50\n1 x cake = 8,00 EUR\nTotal: 33.00 EUR", EUR);
        assertThat(o.lines().get(0).unitMinor()).isEqualTo(1250L);
        assertThat(o.lines().get(1).lineTotalMinor()).isEqualTo(800L);
        assertThat(o.totalMinor()).isEqualTo(3300L);
        assertThat(OrderValidator.validate(o, EUR).verdict()).isEqualTo(ValidationResult.Verdict.OK);
        assertThat(refused("1 x tea @ 1,500", EUR)).as("ambiguous").isEqualTo(FailureReason.INVALID_AMOUNT);
    }

    // ── Refusals ────────────────────────────────────────────────────────────

    @Test
    void empty_and_oversized_input() {
        assertThat(refused(null)).isEqualTo(FailureReason.EMPTY_INPUT);
        assertThat(refused("")).isEqualTo(FailureReason.EMPTY_INPUT);
        assertThat(refused(" \n\t ")).isEqualTo(FailureReason.EMPTY_INPUT);
        assertThat(refused("\u200B\u200B")).isEqualTo(FailureReason.EMPTY_INPUT);
        assertThat(refused("1 x a\n".repeat(700))).isEqualTo(FailureReason.INPUT_TOO_LARGE);
        assertThat(refused("1 x " + "a".repeat(OrderLimits.MAX_INPUT_CHARS))).isEqualTo(FailureReason.INPUT_TOO_LARGE);
        assertThat(ok("1 x " + "a".repeat(OrderLimits.MAX_LABEL))).isNotNull();
    }

    @Test
    void line_and_item_caps() {
        assertThat(refused("1 x a\n".repeat(OrderLimits.MAX_LINES + 1))).isEqualTo(FailureReason.TOO_MANY_LINES);
        assertThat(ok("1 x a\n".repeat(OrderLimits.MAX_ITEMS)).lines()).hasSize(OrderLimits.MAX_ITEMS);
        assertThat(refused("1 x a\n".repeat(OrderLimits.MAX_ITEMS + 1))).isEqualTo(FailureReason.TOO_MANY_ITEMS);
        assertThat(refused("1 x a, ".repeat(OrderLimits.MAX_ITEMS + 1))).isEqualTo(FailureReason.TOO_MANY_ITEMS);
    }

    @Test
    void shapes_the_parser_does_not_understand() {
        assertThat(refused("Total: 6000")).isEqualTo(FailureReason.NO_ITEMS);
        assertThat(refused("Hello there\n2 x rice")).isEqualTo(FailureReason.UNRECOGNIZED_LINE);
        assertThat(refused("I would like two boxes of tomatoes please")).isEqualTo(FailureReason.UNRECOGNIZED_LINE);
        assertThat(refused("2 boxes of tomatoes")).isEqualTo(FailureReason.UNRECOGNIZED_LINE);
        assertThat(refused("2xrice")).as("no space after x").isEqualTo(FailureReason.UNRECOGNIZED_LINE);
        assertThat(ok("2x rice").lines().get(0).qty()).as("no space before x is fine").isEqualTo(2);
        assertThat(refused("2 x")).isEqualTo(FailureReason.UNRECOGNIZED_LINE);
        assertThat(refused("2 \u0445 rice")).as("Cyrillic x").isEqualTo(FailureReason.UNRECOGNIZED_LINE);
        assertThat(refused("2 x rice, wibble 5")).isEqualTo(FailureReason.UNRECOGNIZED_LINE);
    }

    @Test
    void a_field_given_twice_is_refused() {
        for (String text : new String[]{"Total: 5\nTotal: 5\n1 x a", "Name: A\nNom: B\n1 x a", "Order: 1\nRef: 2\n1 x a",
                "Phone: 1234567\nTel: 1234567\n1 x a", "Deliver: a\nLivraison: b\n1 x a"}) {
            assertThat(refused(text)).as(text).isEqualTo(FailureReason.DUPLICATE_FIELD);
        }
    }

    @Test
    void quantities_must_be_one_to_nine_thousand_nine_hundred_ninety_nine() {
        assertThat(ok("9999 x a").lines().get(0).qty()).isEqualTo(9999);
        for (String text : new String[]{"0 x a", "10000 x a", "12345 x a", "99999999999999999999 x a"}) {
            assertThat(refused(text)).as(text).isEqualTo(FailureReason.INVALID_QUANTITY);
        }
    }

    @Test
    void amounts_that_cannot_be_read_exactly() {
        assertThat(refused("2 x a @ abc")).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(refused("2 x a @")).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(refused("2 x a @ 1 00")).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(refused("2 x a @ 0")).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(refused("2 x a @ 5 @ 6")).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(refused("2 x a @ 5 = 6")).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(refused("2 x a @ " + "9".repeat(20))).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        assertThat(refused("2 x a @ 1000000000001")).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        assertThat(refused("3 x a = 1000")).isEqualTo(FailureReason.LINE_TOTAL_NOT_DIVISIBLE);
        assertThat(ok("4 x a = 1000").lines().get(0).lineTotalMinor()).isEqualTo(1000L);
        assertThat(refused("Total: 5 XAF EUR\n1 x a")).isEqualTo(FailureReason.INVALID_AMOUNT);
    }

    @Test
    void two_currencies_in_one_message() {
        assertThat(refused("1 x a @ 5 XAF\n1 x b @ 5 EUR")).isEqualTo(FailureReason.CURRENCY_MISMATCH);
        assertThat(ok("1 x a @ 5 XAF\n1 x b @ 5 FCFA\nTotal: 10 F CFA").currency()).isEqualTo("XAF");
    }

    @Test
    void phone_name_label_and_reference_rules() {
        assertThat(refused("Phone: abc\n1 x a")).isEqualTo(FailureReason.INVALID_PHONE);
        assertThat(refused("Phone: 123\n1 x a")).isEqualTo(FailureReason.INVALID_PHONE);
        assertThat(refused("Phone:\n1 x a")).isEqualTo(FailureReason.INVALID_FIELD);
        assertThat(refused("1 x 123")).as("a label needs a letter").isEqualTo(FailureReason.INVALID_FIELD);
        assertThat(refused("1 x " + "a".repeat(101))).isEqualTo(FailureReason.FIELD_TOO_LONG);
        assertThat(refused("Name: " + "a".repeat(101) + "\n1 x a")).isEqualTo(FailureReason.FIELD_TOO_LONG);
        assertThat(refused("Deliver: " + "a".repeat(301) + "\n1 x a")).isEqualTo(FailureReason.FIELD_TOO_LONG);
        assertThat(refused("Order: " + "a".repeat(65) + "\n1 x a")).isEqualTo(FailureReason.FIELD_TOO_LONG);
        assertThat(refused("Order: a b\n1 x a")).isEqualTo(FailureReason.INVALID_FIELD);
        assertThat(refused("Order: ../x\n1 x a")).isEqualTo(FailureReason.INVALID_FIELD);
        assertThat(ok("Order: #A12\n1 x a").reference()).isEqualTo("A12");
    }

    @Test
    void a_stored_style_label_that_is_not_a_known_label_is_not_a_field() {
        assertThat(refused("Note: hello\n1 x a")).isEqualTo(FailureReason.UNRECOGNIZED_LINE);
        assertThat(ok("1 x rice: basmati @ 500").lines().get(0).label()).as("a colon inside an item is part of the item")
                .isEqualTo("rice: basmati");
    }

    @Test
    void hostile_text_inside_fields_is_kept_as_data_not_interpreted() {
        ParsedOrder o = ok("Name: <b>Ana</b> ${7*7} %s\n1 x <script>x</script> {{7*7}} @ 500");
        assertThat(o.customerName()).isEqualTo("<b>Ana</b> ${7*7} %s");
        assertThat(o.lines().get(0).label()).isEqualTo("<script>x</script> {{7*7}}");
    }

    @Test
    void look_alike_and_invisible_characters_cannot_change_the_amount() {
        assertThat(ok("1 x tomato @ \u0661\u0665\u0660\u0660").lines().get(0).unitMinor()).isEqualTo(1500L);
        assertThat(ok("1 x tomato @ 15\u200B00").lines().get(0).unitMinor()).isEqualTo(1500L);
        // Without the override character 0051 is just a number with a leading zero: refused, not read as 51 or 1500.
        assertThat(refused("1 x tomato @ 5\nTotal: \u202E0051 XAF")).isEqualTo(FailureReason.INVALID_AMOUNT);
    }
}
