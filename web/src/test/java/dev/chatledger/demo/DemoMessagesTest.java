package dev.chatledger.demo;

import dev.chatledger.extraction.OrderParseContext;
import dev.chatledger.extraction.PaymentTemplateSpec;
import dev.chatledger.extraction.PaymentTemplates;
import dev.chatledger.ingestion.OrderDecider;
import dev.chatledger.ingestion.OrderPipeline;
import dev.chatledger.ingestion.PaymentDecider;
import dev.chatledger.ingestion.PaymentPipeline;
import dev.chatledger.payments.ClasspathPaymentTemplateSource;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every text the demo writes goes through the real parsers with the shipped templates and is accepted (tasks.md, task 9): orders become
 * payable with their totals from the demo catalog, confirmations are RECEIVED (UNVERIFIED for the forwarded shape), never UNPARSED, with
 * the time the generator meant, in the template's own zone and format, inside the plausibility window. A property runs this at many
 * clocks, midnights and year ends included. No database: this runs with Docker stopped.
 *
 * <p>Requirement 7. Task 9.
 */
class DemoMessagesTest {

    private static final List<PaymentTemplateSpec> SPECS = new ClasspathPaymentTemplateSource().load();
    private static final PaymentTemplates TEMPLATES = PaymentTemplates.of(SPECS);
    private static final OrderParseContext XAF = new OrderParseContext("XAF", 0);
    private static final Map<String, Long> CATALOG = new HashMap<>();

    static {
        for (DemoCatalog.Row row : DemoCatalog.rows()) {
            CATALOG.put(row.nameKey(), row.unitMinor());
        }
    }

    private static OrderPipeline.Result order(String text) {
        return OrderPipeline.run(text, XAF, keys -> {
            Map<String, Long> found = new HashMap<>();
            keys.forEach(k -> {
                if (CATALOG.containsKey(k)) {
                    found.put(k, CATALOG.get(k));
                }
            });
            return found;
        });
    }

    private static void assertAccepted(Clock clock, Script script, Scenario scenario) {
        OrderPipeline.Result o = order(script.order());
        assertThat(o.draft().status()).as(scenario + " order").isEqualTo(OrderDecider.Status.PENDING_PAYMENT);
        assertThat(o.draft().totalMinor()).as(scenario + " total from the catalog").isEqualTo(script.orderTotalMinor());
        assertThat(o.parsed().phone().inSyntheticRange()).isTrue();
        assertThat(o.parsed().reference()).isEqualTo(script.reference());
        for (String text : script.payments()) {
            PaymentDecider.Draft p = PaymentPipeline.run(text, TEMPLATES, XAF, clock.instant());
            assertThat(p.status()).as(scenario + " payment: " + p.reason()).isIn(PaymentDecider.Status.RECEIVED, PaymentDecider.Status.UNVERIFIED);
            Instant paid = p.parsed().paidAt();
            assertThat(paid).as("never after now").isBeforeOrEqualTo(clock.instant());
            assertThat(paid).as("never older than the demo's oldest").isAfterOrEqualTo(clock.instant().minus(DemoMessages.MAX_AGE).minus(Duration.ofMinutes(1)));
            if (p.parsed().senderPhone() != null) {
                assertThat(p.parsed().senderPhone().inSyntheticRange()).isTrue();
            }
        }
    }

    @Test
    void each_scenario_is_accepted_with_its_amounts_references_and_shapes() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
        DemoMessages m = new DemoMessages(SPECS, clock, new Random(1));
        Map<Scenario, Script> s = new HashMap<>();
        for (Scenario scenario : Scenario.values()) {
            Script script = scenario.script(m);
            s.put(scenario, script);
            assertAccepted(clock, script, scenario);
        }
        // What each scenario is about, read back by the parser (the outcomes on a real board are in DemoScenarioIT).
        assertThat(paid(s.get(Scenario.HAPPY_PATH), 0).amountMinor()).isEqualTo(5000);
        assertThat(paid(s.get(Scenario.HAPPY_PATH), 0).reference()).isEqualTo(s.get(Scenario.HAPPY_PATH).reference());
        assertThat(paid(s.get(Scenario.UNDERPAYMENT), 0).amountMinor()).isEqualTo(3000);
        assertThat(paid(s.get(Scenario.OVERPAYMENT), 0).amountMinor()).isEqualTo(5600);
        assertThat(s.get(Scenario.DUPLICATE).payments()).as("the same confirmation twice").hasSize(2)
                .satisfies(list -> assertThat(list.get(0)).isEqualTo(list.get(1)));
        assertThat(PaymentPipeline.run(s.get(Scenario.SCREENSHOT).payments().get(0), TEMPLATES, XAF, clock.instant()).status())
                .isEqualTo(PaymentDecider.Status.UNVERIFIED);
        assertThat(paid(s.get(Scenario.WRONG_SENDER), 0).senderName()).isEqualTo(DemoNames.STRANGERS.get(0));
        assertThat(paid(s.get(Scenario.WRONG_SENDER), 0).templateId()).isEqualTo("provider-b-recu-ref");
        assertThat(paid(s.get(Scenario.LATE_PAYMENT), 0).paidAt()).isEqualTo(Instant.parse("2026-10-30T12:00:00Z"));
        assertThat(paid(s.get(Scenario.HAPPY_PATH), 0).paidAt()).isEqualTo(Instant.parse("2026-11-03T11:58:00Z"));
    }

    private static dev.chatledger.extraction.ParsedPayment paid(Script script, int index) {
        return PaymentPipeline.run(script.payments().get(index), TEMPLATES, XAF, Instant.parse("2026-11-03T12:00:00Z")).parsed();
    }

    @Test
    void every_shipped_template_gets_a_confirmation_that_it_reads() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
        DemoMessages m = new DemoMessages(SPECS, clock, new Random(2));
        DemoMessages.Payer ana = new DemoMessages.Payer("Ana Testa", DemoNames.seedPhone(1));
        assertThat(SPECS).hasSize(5);
        for (PaymentTemplateSpec spec : SPECS) {
            String text = m.confirmation(spec.id(), ana, 4321, m.takesReference(spec.id()) ? "D-ABCD" : null, Duration.ofMinutes(5));
            PaymentDecider.Draft p = PaymentPipeline.run(text, TEMPLATES, XAF, clock.instant());
            assertThat(p.status()).as(spec.id()).isNotEqualTo(PaymentDecider.Status.UNPARSED);
            assertThat(p.parsed().templateId()).as("read by the template it was written for").isEqualTo(spec.id());
            assertThat(p.parsed().amountMinor()).isEqualTo(4321);
            assertThat(p.parsed().paidAt()).isEqualTo(Instant.parse("2026-11-03T11:55:00Z"));
        }
    }

    @Test
    void the_seed_plan_is_accepted_message_by_message() {
        Clock clock = Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"), ZoneOffset.UTC);   // 00:30 on 1 January at +01:00
        DemoMessages m = new DemoMessages(SPECS, clock, new Random(3));
        int orders = 0;
        int payments = 0;
        for (DemoSeed.Step step : DemoSeed.plan(m)) {
            if (step instanceof DemoSeed.OrderStep o) {
                orders++;
                OrderDecider.Status expected = o.key().equals("unread-order") ? OrderDecider.Status.UNPARSED : OrderDecider.Status.PENDING_PAYMENT;
                assertThat(order(o.text()).draft().status()).as(o.key()).isEqualTo(expected);
            } else if (step instanceof DemoSeed.PaymentStep p) {
                payments++;
                assertThat(PaymentPipeline.run(p.text(), TEMPLATES, XAF, clock.instant()).status()).as(p.key())
                        .isIn(PaymentDecider.Status.RECEIVED, PaymentDecider.Status.UNVERIFIED);
            }
        }
        assertThat(orders).isEqualTo(DemoSeed.ORDERS);
        assertThat(payments).isEqualTo(DemoSeed.PAYMENTS);
    }

    @Test
    void the_demo_uses_only_templates_that_ship() {
        Set<String> ids = new java.util.HashSet<>();
        SPECS.forEach(s -> ids.add(s.id()));
        assertThat(ids).contains(Scenario.TEMPLATE_REF, Scenario.TEMPLATE_FR_REF, Scenario.TEMPLATE_FORWARDED, "provider-a-received");
    }

    /** Any moment from 2026 to 2030 (the minute and the scenario drawn too): every text the demo writes then is accepted. */
    @Property(tries = 300)
    void at_any_clock_every_scenario_is_accepted(@ForAll @LongRange(min = 0, max = 4 * 366 * 24 * 60) long minutes,
                                                 @ForAll @IntRange(min = 0, max = 6) int which, @ForAll @IntRange(min = 0, max = 59) int second) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z").plus(minutes, ChronoUnit.MINUTES).plusSeconds(second);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Scenario scenario = Scenario.values()[which];
        assertAccepted(clock, scenario.script(new DemoMessages(SPECS, clock, new Random(minutes))), scenario);
    }
}
