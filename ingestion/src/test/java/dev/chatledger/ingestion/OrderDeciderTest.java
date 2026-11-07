package dev.chatledger.ingestion;

import dev.chatledger.extraction.ExtractionResult;
import dev.chatledger.extraction.FailureReason;
import dev.chatledger.extraction.OrderLimits;
import dev.chatledger.extraction.OrderParseContext;
import dev.chatledger.extraction.OrderTextParser;
import dev.chatledger.extraction.OrderValidator;
import dev.chatledger.extraction.ParsedOrder;
import dev.chatledger.extraction.TextKeys;
import dev.chatledger.extraction.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.chatledger.ingestion.OrderDecider.Status.NEEDS_REVIEW;
import static dev.chatledger.ingestion.OrderDecider.Status.PENDING_PAYMENT;
import static dev.chatledger.ingestion.OrderDecider.Status.UNPARSED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where each number comes from. Amounts the parser read must be in the message (validator); a total computed from the
 * catalog is not in the message and is checked only for overflow and the cap (decider).
 *
 * <p>Requirements: 1.1, 1.2. Task 4.2.
 */
class OrderDeciderTest {

    private static final OrderParseContext XAF = new OrderParseContext("XAF", 0);
    private static final Map<String, Long> CATALOG = Map.of(
            TextKeys.key("rice"), 1500L, TextKeys.key("beans"), 500L, TextKeys.key("bo\u00EEte de tomates"), 700L);

    private static OrderPipeline.Result run(String text) {
        return OrderPipeline.run(text, XAF, keys -> {
            Map<String, Long> hits = new java.util.HashMap<>();
            keys.forEach(k -> {
                if (CATALOG.containsKey(k)) hits.put(k, CATALOG.get(k));
            });
            return hits;
        });
    }

    // ── The two cases of the validator rule ────────────────────────────────

    @Test
    void case_one_a_total_written_in_the_message_is_checked_against_the_message() {
        OrderPipeline.Result ok = run("2 x rice @ 1500\nTotal: 3000");
        assertThat(ok.draft().status()).isEqualTo(PENDING_PAYMENT);
        assertThat(ok.draft().totalMinor()).isEqualTo(3000L);
    }

    @Test
    void case_one_a_parser_that_reports_an_amount_the_message_does_not_contain_is_stopped_by_the_validator() {
        // A parser that lies: the message has no 9999, the "parse" claims a total of 9999.
        var liar = (dev.chatledger.extraction.MessageParser<ParsedOrder>) text -> new ExtractionResult.Success<>(
                new ParsedOrder(null, null, null, List.of(new ParsedOrder.OrderLine("rice", 2, null, null)), 9999L, null, null, text));
        OrderPipeline.Result r = OrderPipeline.run("2 x rice\nTotal: 3000", XAF, liar, keys -> Map.of());
        assertThat(r.draft().status()).isEqualTo(UNPARSED);
        assertThat(r.draft().reason()).isEqualTo(FailureReason.AMOUNT_NOT_IN_SOURCE);
        assertThat(r.parsed()).isNull();
        assertThat(r.draft().totalMinor()).isNull();
    }

    @Test
    void case_two_a_total_computed_from_the_catalog_is_not_in_the_message_and_is_accepted() {
        String text = "2 x rice\n1 x beans";
        assertThat(text).doesNotContain("3500");
        OrderPipeline.Result r = run(text);
        assertThat(r.draft().status()).isEqualTo(PENDING_PAYMENT);
        assertThat(r.draft().totalMinor()).as("2 x 1500 + 1 x 500, computed, not written anywhere").isEqualTo(3500L);
        assertThat(r.draft().lines()).extracting(OrderDecider.Line::unitMinor).containsExactly(1500L, 500L);
    }

    @Test
    void case_two_also_with_french_names_and_accents() {
        OrderPipeline.Result r = run("2 x Bo\u00EEte de TOMATES");
        assertThat(r.draft().totalMinor()).isEqualTo(1400L);
        assertThat(run("2 x boite de tomates").draft().totalMinor()).as("accent-insensitive key").isEqualTo(1400L);
    }

    @Test
    void the_computed_total_still_has_a_cap_and_exact_arithmetic() {
        Map<String, Long> big = Map.of(TextKeys.key("gold"), OrderLimits.MAX_MINOR);
        OrderPipeline.Result r = OrderPipeline.run("2 x gold", XAF, keys -> big);
        assertThat(r.draft().status()).isEqualTo(UNPARSED);
        assertThat(r.draft().reason()).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        assertThat(OrderPipeline.run("1 x gold", XAF, keys -> big).draft().totalMinor()).isEqualTo(OrderLimits.MAX_MINOR);

        StringBuilder fifty = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            fifty.append("9999 x gold\n");
        }
        assertThat(OrderPipeline.run(fifty.toString(), XAF, keys -> big).draft().reason()).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
    }

    // ── Statuses ───────────────────────────────────────────────────────────

    @Test
    void an_unknown_price_without_a_stated_total_needs_review_and_has_no_total() {
        OrderPipeline.Result r = run("2 x rice\n1 x mystery");
        assertThat(r.draft().status()).isEqualTo(NEEDS_REVIEW);
        assertThat(r.draft().totalMinor()).isNull();
        assertThat(r.draft().reason()).isEqualTo(FailureReason.PRICE_UNKNOWN);
        assertThat(r.draft().lines()).extracting(OrderDecider.Line::unitMinor).containsExactly(1500L, null);
        assertThat(r.draft().currency()).as("always the tenant's").isEqualTo("XAF");
    }

    @Test
    void a_stated_total_is_enough_even_when_some_prices_are_unknown() {
        OrderPipeline.Result r = run("2 x rice\n1 x mystery\nTotal: 5000");
        assertThat(r.draft().status()).isEqualTo(PENDING_PAYMENT);
        assertThat(r.draft().totalMinor()).isEqualTo(5000L);
    }

    @Test
    void a_stated_total_is_never_replaced_by_a_computed_one() {
        OrderPipeline.Result fromMessage = run("2 x rice @ 1500\nTotal: 3500");
        assertThat(fromMessage.draft().status()).isEqualTo(NEEDS_REVIEW);
        assertThat(fromMessage.draft().totalMinor()).isEqualTo(3500L);
        assertThat(fromMessage.draft().reason()).isEqualTo(FailureReason.TOTAL_MISMATCH);

        OrderPipeline.Result fromCatalog = run("2 x rice\nTotal: 3500");
        assertThat(fromCatalog.draft().status()).isEqualTo(NEEDS_REVIEW);
        assertThat(fromCatalog.draft().totalMinor()).as("the stated number is kept").isEqualTo(3500L);
        assertThat(fromCatalog.draft().reason()).isEqualTo(FailureReason.TOTAL_MISMATCH);

        assertThat(run("2 x rice\nTotal: 3000").draft().status()).as("agreeing is fine").isEqualTo(PENDING_PAYMENT);
    }

    @Test
    void a_price_written_in_the_message_wins_over_the_catalog() {
        OrderPipeline.Result r = run("1 x rice @ 1000");
        assertThat(r.draft().totalMinor()).isEqualTo(1000L);
        assertThat(run("2 x rice = 3000").draft().lines().get(0).unitMinor()).isEqualTo(1500L);
    }

    @Test
    void catalog_prices_outside_the_valid_range_are_ignored() {
        for (long bad : new long[]{0, -5, OrderLimits.MAX_MINOR + 1, Long.MAX_VALUE, Long.MIN_VALUE}) {
            Map<String, Long> prices = Map.of(TextKeys.key("rice"), bad);
            OrderPipeline.Result r = OrderPipeline.run("1 x rice", XAF, keys -> prices);
            assertThat(r.draft().status()).as("price " + bad).isEqualTo(NEEDS_REVIEW);
            assertThat(r.draft().totalMinor()).isNull();
        }
    }

    @Test
    void the_catalog_is_asked_only_for_items_without_a_price_in_the_message() {
        List<Set<String>> asked = new ArrayList<>();
        OrderPipeline.run("1 x rice @ 5\n1 x beans\n2 x tea = 10", XAF, keys -> {
            asked.add(keys);
            return Map.of();
        });
        assertThat(asked).containsExactly(Set.of(TextKeys.key("beans")));
        OrderPipeline.run("1 x rice @ 5", XAF, keys -> {
            throw new AssertionError("no item needs the catalog");
        });
    }

    // ── Unparsed and rejected ──────────────────────────────────────────────

    @Test
    void messages_the_parser_or_validator_refuse_become_unparsed_with_the_tenants_currency() {
        for (String text : new String[]{"hello", "Total: 5", "1 x rice @ 5 EUR", "1 x rice @ 1,5"}) {
            OrderPipeline.Result r = run(text);
            assertThat(r.draft().status()).as(text).isEqualTo(UNPARSED);
            assertThat(r.draft().totalMinor()).isNull();
            assertThat(r.draft().currency()).isEqualTo("XAF");
            assertThat(r.draft().lines()).isEmpty();
            assertThat(r.draft().reason()).isNotNull();
            assertThat(r.parsed()).isNull();
        }
    }

    @Test
    void the_validators_review_verdict_is_carried_through() {
        ParsedOrder p = new OrderTextParser(XAF).parse("2 x rice @ 1500\nTotal: 3500").value().orElseThrow();
        ValidationResult v = OrderValidator.validate(p, XAF);
        assertThat(v.verdict()).isEqualTo(ValidationResult.Verdict.NEEDS_REVIEW);
        assertThat(OrderDecider.decide(p, v, Map.of(), "XAF").status()).isEqualTo(NEEDS_REVIEW);
    }

    @Test
    void a_lines_toString_prints_no_label() {
        assertThat(new OrderDecider.Line("secret label", 3, 5L).toString()).isEqualTo("Line[qty=3]");
    }
}
