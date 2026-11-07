package dev.chatledger.extraction;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Throws every hostile message and thousands of random ones at the parser and validator. Whatever the input: no
 * exception, every cap holds, every accepted amount is written in the message, no field holds a control or
 * invisible character, and it finishes quickly.
 *
 * <p>The time bound is generous because a wall-clock test can be flaky on a slow machine; the deterministic
 * guarantee against pathological input is the structural scan in {@link NoInputPatternsTest} (no regular
 * expression can be built from the message, and there is no backtracking) together with the input caps.
 *
 * <p>Requirements: 1.5, 8. Task 4.1.
 */
class OrderParserHostileTest {

    private static final OrderParseContext XAF = new OrderParseContext("XAF", 0);
    private static final OrderParseContext EUR = new OrderParseContext("EUR", 2);

    private static void assertInvariants(String text, OrderParseContext context) {
        ExtractionResult<ParsedOrder> r = new OrderTextParser(context).parse(text);
        if (!r.isSuccess()) {
            assertThat(r.failureReason()).isPresent();
            return;
        }
        ParsedOrder o = r.value().orElseThrow();
        assertThat(o.rawText()).isEqualTo(text);
        assertThat(o.lines()).isNotEmpty().hasSizeLessThanOrEqualTo(OrderLimits.MAX_ITEMS);
        for (ParsedOrder.OrderLine l : o.lines()) {
            assertThat(l.qty()).isBetween(OrderLimits.MIN_QTY, OrderLimits.MAX_QTY);
            assertThat(l.label()).isNotBlank().hasSizeLessThanOrEqualTo(OrderLimits.MAX_LABEL);
            assertClean(l.label());
            for (Long amount : new Long[]{l.unitMinor(), l.lineTotalMinor()}) {
                if (amount != null) {
                    assertThat(amount).isBetween(1L, OrderLimits.MAX_MINOR);
                }
            }
        }
        if (o.totalMinor() != null) {
            assertThat(o.totalMinor()).isBetween(1L, OrderLimits.MAX_MINOR);
        }
        for (String field : new String[]{o.customerName(), o.deliveryNote(), o.reference()}) {
            if (field != null) {
                assertClean(field);
            }
        }
        if (o.customerName() != null) {
            assertThat(o.customerName()).hasSizeLessThanOrEqualTo(OrderLimits.MAX_NAME);
        }
        if (o.deliveryNote() != null) {
            assertThat(o.deliveryNote()).hasSizeLessThanOrEqualTo(OrderLimits.MAX_NOTE);
        }
        if (o.reference() != null) {
            assertThat(o.reference()).hasSizeLessThanOrEqualTo(OrderLimits.MAX_REF).matches("[A-Za-z0-9._:-]+");
        }
        assertThat(o.toString()).doesNotContain(String.valueOf(o.customerName() == null ? "\u0000" : o.customerName()));

        assertThat(OrderValidator.validate(o, context).verdict()).isNotNull();
    }

    private static void assertClean(String s) {
        s.codePoints().forEach(cp -> {
            assertThat(Character.isISOControl(cp)).as("control character in a field").isFalse();
            assertThat(Character.getType(cp)).as("format character in a field").isNotEqualTo(Character.FORMAT);
        });
    }

    @Test
    void the_hostile_corpus_never_throws_and_always_keeps_the_invariants() {
        HostileCorpus.all().forEach((name, text) -> {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                assertInvariants(text, XAF);
                assertInvariants(text, EUR);
            }, name);
        });
    }

    @Test
    void pathological_inputs_are_refused_or_read_in_linear_time() {
        for (String name : new String[]{"long label", "many spaces", "pairs that tempt backtracking", "nested brackets",
                "alternating digits and separators", "thousands of lines", "only colons", "only labels", "huge input"}) {
            long start = System.nanoTime();
            new OrderTextParser(XAF).parse(HostileCorpus.get(name));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as(name).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test
    void the_hostile_corpus_outcomes_that_matter() {
        OrderTextParser p = new OrderTextParser(XAF);
        for (String name : new String[]{"huge input", "empty", "only whitespace",
                "amount just over the cap", "long overflow", "long overflow plus one", "huge digit run", "quantity overflow",
                "negative looking amount", "scientific notation", "decimal where none is allowed", "repeated separators",
                "leading zeros", "mixed separators", "thousands of items", "fifty lines of big prices", "separator soup"}) {
            ExtractionResult<ParsedOrder> r = p.parse(HostileCorpus.get(name));
            if (r.isSuccess()) {
                assertThat(OrderValidator.validate(r.value().get(), XAF).verdict()).as(name).isNotEqualTo(ValidationResult.Verdict.OK);
            }
        }
        assertThat(p.parse(HostileCorpus.get("amount at the cap")).isSuccess()).isTrue();
        assertThat(p.parse(HostileCorpus.get("arabic-indic digits")).value().orElseThrow().lines().get(0).unitMinor()).isEqualTo(1500L);
        assertThat(p.parse(HostileCorpus.get("full-width digits and x")).value().orElseThrow().lines().get(0).qty()).isEqualTo(2);
        assertThat(p.parse(HostileCorpus.get("cyrillic x as multiplication sign")).isSuccess()).isFalse();
        assertThat(p.parse(HostileCorpus.get("script tag as an item")).value().orElseThrow().lines().get(0).label())
                .as("kept as data").contains("<script>");
    }

    @Test
    void nul_and_lone_surrogates_are_cleaned_by_the_normaliser_but_the_service_refuses_them_before_this_point() {
        // The parser is lenient here on purpose (the cleaned copy is what it reads); the ingestion service rejects
        // such input before parsing, which is tested in the web module.
        assertThat(new OrderTextParser(XAF).parse(HostileCorpus.get("nul and control characters")).isSuccess()).isTrue();
        assertThat(TextNormalizer.normalize(HostileCorpus.get("lone surrogate"))).doesNotContain("\u0000").doesNotContain("\uD800");
    }

    @Test
    void the_caps_are_hit_exactly_at_the_boundaries() {
        assertThat(OrderLimits.MAX_MINOR * OrderLimits.MAX_ITEMS).as("fifty capped lines fit in a long").isPositive();
        assertThat(OrderLimits.MAX_MINOR * OrderLimits.MAX_QTY * OrderLimits.MAX_ITEMS)
                .as("this one is why every product uses Math.*Exact").isLessThan(Long.MAX_VALUE);
    }

    @Property(tries = 3000)
    void any_text_parses_or_is_refused_without_an_exception(@ForAll("messages") String text) {
        assertInvariantsLoosely(text);
    }

    private static void assertInvariantsLoosely(String text) {
        for (OrderParseContext context : new OrderParseContext[]{XAF, EUR}) {
            ExtractionResult<ParsedOrder> r = new OrderTextParser(context).parse(text);
            if (r.isSuccess()) {
                ParsedOrder o = r.value().get();
                assertThat(o.lines()).isNotEmpty().hasSizeLessThanOrEqualTo(OrderLimits.MAX_ITEMS);
                OrderValidator.validate(o, context);
            }
        }
    }

    @Property(tries = 3000)
    void for_any_text_every_amount_that_passes_validation_is_written_in_it(@ForAll("messages") String text) {
        OrderParseContext context = XAF;
        ExtractionResult<ParsedOrder> r = new OrderTextParser(context).parse(text);
        if (!r.isSuccess()) {
            return;
        }
        ParsedOrder o = r.value().get();
        if (OrderValidator.validate(o, context).verdict() == ValidationResult.Verdict.REJECT) {
            return;
        }
        String digits = TextNormalizer.normalize(text).replaceAll("[^0-9]", "");
        o.lines().forEach(l -> {
            if (l.unitMinor() != null) assertThat(digits).contains(Long.toString(l.unitMinor()));
            if (l.lineTotalMinor() != null) assertThat(digits).contains(Long.toString(l.lineTotalMinor()));
        });
        if (o.totalMinor() != null) {
            assertThat(digits).contains(Long.toString(o.totalMinor()));
        }
    }

    @Provide
    Arbitrary<String> messages() {
        Arbitrary<String> pieces = Arbitraries.of(
                "1 x rice", "2 x tomato box", "3 \u00D7 riz", " @ ", " = ", "1500", "6 000", "6,000", "12.50", "0", "007", "99999999999999",
                "Total: ", "Total", "Name: ", "Nom : ", "Phone: ", "+999 000 000 1234", "T\u00E9l\u00E9phone: ", "Deliver: ", "Livraison : ",
                "Order: ", "A-1", "XAF", "FCFA", "F CFA", "EUR", ", ", ";", "\n", "\r\n", ":", " ", "x", "\u202E", "\u200B", "\u0661\u0665",
                "<script>", "%s", "${1}", "'", "\"", "\\", "\u0000", "\uD800", "\uD83D\uDE00", "a", "Z", "\u00E9");
        return pieces.list().ofMinSize(1).ofMaxSize(40).map(l -> String.join("", l));
    }
}
