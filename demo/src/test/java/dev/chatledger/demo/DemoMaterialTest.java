package dev.chatledger.demo;

import dev.chatledger.extraction.HoleType;
import dev.chatledger.extraction.PaymentTemplateSpec;
import dev.chatledger.extraction.PhoneNumbers;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The demo's synthetic material on its own: names, phones, references, the catalog, the bounds of the generator. The round trip through
 * the real parsers with the shipped templates is in the web module ({@code DemoMessagesTest}), where those templates live.
 *
 * <p>Requirement 7.3. Task 9.
 */
class DemoMaterialTest {

    /** A minimal spec of this test's own (not a shipped template), to exercise the generator's checks. */
    private static final PaymentTemplateSpec SPEC = new PaymentTemplateSpec("test-ref", "PROVIDER_T", "XAF", 0, true, "+01:00",
            "dd/MM/yyyy HH:mm", "Got {amount} {currency} from {senderName} ({senderPhone}). Txn {transactionId}. Ref {reference}. {paidAt}",
            List.of(), List.of());

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T23:30:00Z"), ZoneOffset.UTC);

    @Test
    void every_name_is_on_the_list_and_no_two_people_share_a_first_letter_or_a_surname() {
        List<String> all = new java.util.ArrayList<>(DemoNames.SEED);
        all.addAll(DemoNames.SCENARIO);
        all.addAll(DemoNames.STRANGERS);
        Set<Character> initials = new HashSet<>();
        Set<String> surnames = new HashSet<>();
        for (String name : all) {
            assertThat(DemoNames.allowed(name)).isTrue();
            assertThat(initials.add(name.charAt(0))).as(name + ": its initial is not shared").isTrue();
            assertThat(surnames.add(name.substring(name.indexOf(' ') + 1))).as(name + ": its surname is not shared").isTrue();
        }
        assertThat(DemoNames.SCENARIO).hasSize(Scenario.values().length);
        assertThat(DemoNames.allowed("Somebody Real")).isFalse();
        assertThatThrownBy(() -> new DemoMessages.Payer("Somebody Real", DemoNames.seedPhone(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemoMessages.Payer("Ana Testa", "+237 600 000 001")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void seed_phones_and_fresh_phones_are_synthetic_canonical_and_never_the_same_range() {
        for (int i = 0; i <= 999; i += 37) {
            String phone = DemoNames.seedPhone(i);
            assertThat(DemoNames.syntheticPhone(phone)).isTrue();
            assertThat(PhoneNumbers.canonicalDigits(phone)).get().asString().startsWith("999000").hasSize(13);
        }
        assertThatThrownBy(() -> DemoNames.seedPhone(1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoNames.seedPhone(-1)).isInstanceOf(IllegalArgumentException.class);
        Random random = new Random(7);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String phone = DemoNames.freshPhone(random);
            assertThat(phone).matches("\\+999 000 0[1-9][0-9] [0-9]{4}");
            assertThat(PhoneNumbers.canonicalDigits(phone)).get().asString().hasSize(13);
            seen.add(phone);
        }
        assertThat(seen.size()).as("six random digits rarely repeat").isGreaterThan(1990);
        assertThat(DemoNames.syntheticPhone("+237 600 000 001")).isFalse();
        assertThat(DemoNames.syntheticPhone(null)).isFalse();
    }

    @Test
    void references_and_transaction_ids_have_their_shape_and_do_not_repeat_in_ten_thousand_draws() {
        DemoMessages m = new DemoMessages(List.of(SPEC), clock, new Random(11));
        Set<String> references = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String ref = m.reference();
            assertThat(ref).matches("D-[A-HJ-NP-Z2-9]{4}");
            references.add(ref);
        }
        // 32^4 = 1,048,576 codes: ten thousand draws hold at most a few dozen repeats, and a workspace holds at most 300 orders.
        assertThat(references.size()).isGreaterThan(9_900);
        assertThat(m.transactionId()).matches("DT[A-HJ-NP-Z2-9]{8}");
    }

    @Test
    void the_catalog_has_eight_items_with_an_english_and_a_french_row_each() {
        assertThat(DemoCatalog.ITEMS).hasSize(8);
        assertThat(DemoCatalog.rows()).hasSize(16);
        assertThat(DemoCatalog.rows()).extracting(DemoCatalog.Row::nameKey).doesNotHaveDuplicates().contains("boite de tomates", "rice 5kg");
        assertThat(DemoCatalog.rows()).allSatisfy(r -> assertThat(r.unitMinor()).isBetween(1L, 1_000_000L));
    }

    @Test
    void a_confirmation_fills_every_hole_and_refuses_what_its_template_would_not_read() {
        DemoMessages m = new DemoMessages(List.of(SPEC), clock, new Random(3));
        DemoMessages.Payer ana = new DemoMessages.Payer("Ana Testa", DemoNames.seedPhone(1));
        String text = m.confirmation("test-ref", ana, 5000, "D-ABCD", Duration.ofMinutes(5));
        // 23:25 UTC is 00:25 the next day at +01:00: the zone is the template's own.
        assertThat(text).startsWith("Got 5000 XAF from Ana Testa (+999 000 000 1001). Txn DT").endsWith(". Ref D-ABCD. 04/11/2026 00:25");
        assertThatThrownBy(() -> m.confirmation("test-ref", ana, 5000, null, Duration.ZERO)).as("its reference hole needs a value")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.confirmation("test-ref", ana, 5000, "D AB", Duration.ZERO)).as("a space ends a reference")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.confirmation("other", ana, 5000, "D-ABCD", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.confirmation("test-ref", ana, 0, "D-ABCD", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.confirmation("test-ref", ana, 5000, "D-ABCD", Duration.ofMinutes(-1))).as("never ahead of now")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.confirmation("test-ref", ana, 5000, "D-ABCD", DemoMessages.MAX_AGE.plusMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(m.confirmation("test-ref", ana, 5000, "D-ABCD", DemoMessages.MAX_AGE)).endsWith("31/10/2026 00:30");
        DemoMessages cents = new DemoMessages(List.of(new PaymentTemplateSpec("test-cents", "PROVIDER_T", "EUR", 2, true, "+01:00", "dd/MM/yyyy HH:mm",
                SPEC.template(), List.of(), List.of())), clock, new Random(4));
        assertThatThrownBy(() -> cents.confirmation("test-cents", ana, 5000, "D-ABCD", Duration.ZERO)).as("a currency with decimals")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(m.takesReference("test-ref")).isTrue();
        assertThat(m.takesReference("other")).isFalse();
    }

    @Test
    void the_filler_refuses_an_unknown_or_unclosed_hole_and_a_value_too_long() {
        Map<HoleType, String> values = new EnumMap<>(HoleType.class);
        values.put(HoleType.AMOUNT, "1");
        assertThat(DemoMessages.fill("Paid {amount}.", values)).isEqualTo("Paid 1.");
        assertThatThrownBy(() -> DemoMessages.fill("Paid {nothing}.", values)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoMessages.fill("Paid {amount", values)).isInstanceOf(IllegalArgumentException.class);
        values.put(HoleType.AMOUNT, "1".repeat(21));
        assertThatThrownBy(() -> DemoMessages.fill("Paid {amount}.", values)).isInstanceOf(IllegalArgumentException.class);
        values.put(HoleType.AMOUNT, "");
        assertThatThrownBy(() -> DemoMessages.fill("Paid {amount}.", values)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_order_names_its_items_without_prices_in_either_language() {
        DemoMessages m = new DemoMessages(List.of(SPEC), clock, new Random(5));
        DemoMessages.Payer bea = new DemoMessages.Payer("Bea Sample", DemoNames.seedPhone(2));
        List<DemoMessages.Line> lines = List.of(new DemoMessages.Line(2, DemoCatalog.RICE), new DemoMessages.Line(1, DemoCatalog.TOMATOES));
        assertThat(m.order(DemoMessages.Language.ENGLISH, "D-AAAA", bea, lines))
                .isEqualTo("Order: D-AAAA\nName: Bea Sample\nPhone: +999 000 000 1002\n2 x rice 5kg\n1 x tomato box");
        assertThat(m.order(DemoMessages.Language.FRENCH, null, bea, lines))
                .isEqualTo("Nom: Bea Sample\nTél: +999 000 000 1002\n2 x riz 5kg\n1 x boîte de tomates");
        assertThat(m.oneLineOrder(bea, lines)).isEqualTo("2 x rice 5kg, 1 x tomato box, from Bea Sample, phone +999 000 000 1002");
        assertThat(DemoMessages.total(lines)).isEqualTo(6500);
        assertThatThrownBy(() -> m.order(DemoMessages.Language.ENGLISH, null, bea, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemoMessages.Line(0, DemoCatalog.RICE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemoMessages.Line(100, DemoCatalog.RICE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_seed_plan_has_its_ten_orders_and_eight_payments_and_unique_keys() {
        DemoMessages m = new DemoMessages(List.of(
                SPEC, spec("provider-a-received-ref", "Got {amount} {currency} from {senderName} ({senderPhone}). Txn {transactionId}. Ref {reference}. {paidAt}"),
                spec("provider-a-received", "Got {amount} {currency} from {senderName} ({senderPhone}). Txn {transactionId}. {paidAt}"),
                spec("provider-b-recu-ref", "Recu {amount} {currency} de {senderName} ({senderPhone}). ID {transactionId}. Motif : {reference}. Le {paidAt}"),
                spec("provider-a-forwarded", "Forwarded {amount} {currency} from {senderName} ({senderLast4}) on {paidAt}")), clock, new Random(1));
        List<DemoSeed.Step> plan = DemoSeed.plan(m);
        assertThat(plan.stream().filter(s -> s instanceof DemoSeed.OrderStep)).hasSize(DemoSeed.ORDERS);
        assertThat(plan.stream().filter(s -> s instanceof DemoSeed.PaymentStep)).hasSize(DemoSeed.PAYMENTS);
        assertThat(plan).extracting(DemoSeed.Step::key).doesNotHaveDuplicates();
        for (Scenario s : Scenario.values()) {
            Script script = s.script(m);
            assertThat(Scenario.byPath(s.path())).contains(s);
            assertThat(script.customerName()).isEqualTo(DemoNames.SCENARIO.get(s.ordinal()));
            assertThat(script.payments()).isNotEmpty();
        }
        assertThat(Scenario.byPath("nothing")).isEmpty();
    }

    private static PaymentTemplateSpec spec(String id, String template) {
        return new PaymentTemplateSpec(id, "PROVIDER_T", "XAF", 0, true, "+01:00", "dd/MM/yyyy HH:mm", template, List.of(), List.of());
    }

    @Property(tries = 200)
    void every_age_inside_the_bounds_is_written_and_never_after_now(@ForAll @LongRange(min = 0, max = 4 * 24 * 60 * 60) long seconds) {
        DemoMessages m = new DemoMessages(List.of(SPEC), clock, new Random(seconds));
        String text = m.confirmation("test-ref", new DemoMessages.Payer("Ana Testa", DemoNames.seedPhone(1)), 100, "D-ABCD",
                Duration.ofSeconds(seconds));
        assertThat(text).matches(".* [0-3][0-9]/1[01]/2026 [0-2][0-9]:[0-5][0-9]");
    }

    /** The demo workspace's zone (D18) is the templates' +01:00 all year: no daylight saving to move "today" against the confirmations. */
    @Test
    void the_demo_zone_is_utc_plus_one_all_year() {
        java.time.ZoneId douala = java.time.ZoneId.of("Africa/Douala");
        for (int month = 1; month <= 12; month++) {
            assertThat(douala.getRules().getOffset(Instant.parse(String.format("2027-%02d-15T12:00:00Z", month)))).isEqualTo(ZoneOffset.ofHours(1));
        }
        assertThat(douala.getRules().nextTransition(Instant.parse("2026-01-01T00:00:00Z"))).isNull();
    }
}
