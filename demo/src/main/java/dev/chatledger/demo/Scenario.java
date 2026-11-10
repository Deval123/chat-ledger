package dev.chatledger.demo;

import dev.chatledger.demo.DemoMessages.Language;
import dev.chatledger.demo.DemoMessages.Line;
import dev.chatledger.demo.DemoMessages.Payer;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The seven one-click scenarios of requirement 7.2, in the order of the demo story (design stop, sections 3 and 5). Each makes a fresh
 * script: a new order code, a new synthetic phone, and times relative to now. The expected outcome of each is in its comment and in
 * {@code DemoScenarioIT}.
 */
public enum Scenario {

    /** Exact amount, its reference, same phone and name, paid two minutes ago: matched automatically. */
    HAPPY_PATH("happy-path") {
        @Override
        public Script script(DemoMessages m) {
            Payer customer = customer(m);
            String ref = m.reference();
            List<Line> lines = List.of(new Line(2, DemoCatalog.RICE));
            long total = DemoMessages.total(lines);
            return new Script(customer.name(), ref, total, m.order(Language.ENGLISH, ref, customer, lines),
                    List.of(m.confirmation(TEMPLATE_REF, customer, total, ref, PAID_JUST_NOW)), List.of("Paid, thank you!"));
        }
    },
    /** 3,000 of 5,000 with the reference: a suggestion (not exact, so never automatic); confirmed, the order is part paid. */
    UNDERPAYMENT("underpayment") {
        @Override
        public Script script(DemoMessages m) {
            Payer customer = customer(m);
            String ref = m.reference();
            List<Line> lines = List.of(new Line(2, DemoCatalog.RICE));
            return new Script(customer.name(), ref, DemoMessages.total(lines), m.order(Language.ENGLISH, ref, customer, lines),
                    List.of(m.confirmation(TEMPLATE_REF, customer, 3000, ref, PAID_JUST_NOW)), List.of("I sent part of it, the rest tomorrow."));
        }
    },
    /** 5,600 for 5,000: R11 (overpayment) on the pair, a suggestion; confirmed, 600 goes to customer credit. */
    OVERPAYMENT("overpayment") {
        @Override
        public Script script(DemoMessages m) {
            Payer customer = customer(m);
            String ref = m.reference();
            List<Line> lines = List.of(new Line(2, DemoCatalog.RICE));
            return new Script(customer.name(), ref, DemoMessages.total(lines), m.order(Language.ENGLISH, ref, customer, lines),
                    List.of(m.confirmation(TEMPLATE_REF, customer, 5600, ref, PAID_JUST_NOW)),
                    List.of("I sent too much by mistake, please send back the difference."));
        }
    },
    /** The happy path's confirmation, then the same confirmation again: the second is a repeat (R1), nothing posted for it. */
    DUPLICATE("duplicate") {
        @Override
        public Script script(DemoMessages m) {
            Payer customer = customer(m);
            String ref = m.reference();
            List<Line> lines = List.of(new Line(2, DemoCatalog.RICE));
            long total = DemoMessages.total(lines);
            String confirmation = m.confirmation(TEMPLATE_REF, customer, total, ref, PAID_JUST_NOW);
            return new Script(customer.name(), ref, total, m.order(Language.ENGLISH, ref, customer, lines),
                    List.of(confirmation, confirmation), List.of("Paid! Here is the message again in case."));
        }
    },
    /** A forwarded text with no transaction id and only the last four digits: unverified (R2), never automatic. */
    SCREENSHOT("screenshot") {
        @Override
        public Script script(DemoMessages m) {
            Payer customer = customer(m);
            String ref = m.reference();
            List<Line> lines = List.of(new Line(1, DemoCatalog.PLANTAIN));
            long total = DemoMessages.total(lines);
            return new Script(customer.name(), ref, total, m.order(Language.ENGLISH, ref, customer, lines),
                    List.of(m.confirmation(TEMPLATE_FORWARDED, customer, total, null, PAID_JUST_NOW)),
                    List.of("Here is a screenshot of my payment."));
        }
    },
    /** The reference, but from another phone and under a name unlike the customer's (French template): R8 and R7, a suggestion. */
    WRONG_SENDER("wrong-sender") {
        @Override
        public Script script(DemoMessages m) {
            Payer customer = customer(m);
            Payer stranger = new Payer(DemoNames.STRANGERS.get(0), m.freshPhone());
            String ref = m.reference();
            List<Line> lines = List.of(new Line(2, DemoCatalog.RICE));
            long total = DemoMessages.total(lines);
            return new Script(customer.name(), ref, total, m.order(Language.FRENCH, ref, customer, lines),
                    List.of(m.confirmation(TEMPLATE_FR_REF, stranger, total, ref, PAID_JUST_NOW)),
                    List.of("C'est mon frère qui a payé."));
        }
    },
    /** A confirmation paid four days ago for an order placed now: R9 (far from when it was recorded) and R5 (paid before the order). */
    LATE_PAYMENT("late-payment") {
        @Override
        public Script script(DemoMessages m) {
            Payer customer = customer(m);
            String ref = m.reference();
            List<Line> lines = List.of(new Line(2, DemoCatalog.RICE));
            long total = DemoMessages.total(lines);
            return new Script(customer.name(), ref, total, m.order(Language.ENGLISH, ref, customer, lines),
                    List.of(m.confirmation(TEMPLATE_REF, customer, total, ref, Duration.ofDays(4))),
                    List.of("I paid already, here is the message."));
        }
    };

    static final String TEMPLATE_REF = "provider-a-received-ref";
    static final String TEMPLATE_FR_REF = "provider-b-recu-ref";
    static final String TEMPLATE_FORWARDED = "provider-a-forwarded";
    static final Duration PAID_JUST_NOW = Duration.ofMinutes(2);

    private final String path;

    Scenario(String path) {
        this.path = path;
    }

    /** The path segment of the scenario's route. */
    public String path() {
        return path;
    }

    /** The scenario's customer: its own fictional name, a fresh synthetic phone. */
    Payer customer(DemoMessages m) {
        return new Payer(DemoNames.SCENARIO.get(ordinal()), m.freshPhone());
    }

    public abstract Script script(DemoMessages m);

    public static Optional<Scenario> byPath(String path) {
        for (Scenario s : values()) {
            if (s.path.equals(path)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
