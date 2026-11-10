package dev.chatledger.demo;

import dev.chatledger.demo.DemoMessages.Language;
import dev.chatledger.demo.DemoMessages.Line;
import dev.chatledger.demo.DemoMessages.Payer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What a fresh demo workspace holds before its visitor arrives (tasks.md, task 9): every matching flow and the flags R1, R5, R8, R10 and
 * R11, written in this order through the same ingestion services as real input. Each step has a key that becomes part of its external id,
 * so a seed that is run again replays and adds nothing.
 *
 * <p>Expected outcomes (asserted by {@code DemoSeedIT}): auto, a repeat (R1), a suggestion without a reference, a part payment confirmed by
 * the seller, an overpayment (R11), an unverified payment (R2), an unread order, a payment made hours before its order (R5), a sender
 * unlike the customer (R8, R7), and one phone under two names (R10).
 */
public final class DemoSeed {

    /** One write of the seed. */
    public sealed interface Step permits OrderStep, PaymentStep, ConfirmStep {
        String key();
    }

    public record OrderStep(String key, String text) implements Step {
    }

    public record PaymentStep(String key, String text) implements Step {
    }

    /** The seller confirms the suggestion between an order and a payment written earlier in the seed. */
    public record ConfirmStep(String key, String orderKey, String paymentKey) implements Step {
    }

    public static final int ORDERS = 10;
    public static final int PAYMENTS = 8;

    private static final String REF = Scenario.TEMPLATE_REF;
    private static final String PLAIN = "provider-a-received";
    private static final String FR_REF = Scenario.TEMPLATE_FR_REF;
    private static final String FORWARDED = Scenario.TEMPLATE_FORWARDED;
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    private DemoSeed() {
    }

    public static List<Step> plan(DemoMessages m) {
        List<Step> steps = new ArrayList<>();
        Payer ana = seed(0);
        Payer bea = seed(1);
        Payer cy = seed(2);
        Payer dee = seed(3);
        Payer eve = seed(4);
        Payer fay = new Payer(DemoNames.SEED.get(5), eve.phone());   // Eve's phone under another name: R10
        Payer gus = seed(6);
        Payer hal = seed(7);
        Payer ivy = seed(8);
        Payer kim = new Payer(DemoNames.STRANGERS.get(0), DemoNames.seedPhone(900));

        // Auto: exact, its reference, same phone and name.
        String autoRef = m.reference();
        List<Line> autoLines = List.of(new Line(2, DemoCatalog.RICE));
        steps.add(new OrderStep("auto-order", m.order(Language.ENGLISH, autoRef, ana, autoLines)));
        String autoPayment = m.confirmation(REF, ana, DemoMessages.total(autoLines), autoRef, FIVE_MINUTES);
        steps.add(new PaymentStep("auto-payment", autoPayment));
        // R1: the same confirmation again.
        steps.add(new PaymentStep("repeat-payment", autoPayment));

        // Suggested: one-line order with no reference; exact amount, same phone and name.
        List<Line> suggestedLines = List.of(new Line(2, DemoCatalog.TOMATOES), new Line(1, DemoCatalog.PLANTAIN));
        steps.add(new OrderStep("suggested-order", m.oneLineOrder(bea, suggestedLines)));
        steps.add(new PaymentStep("suggested-payment", m.confirmation(PLAIN, bea, DemoMessages.total(suggestedLines), null, FIVE_MINUTES)));

        // Partial, in French, confirmed by the seller.
        String partialRef = m.reference();
        steps.add(new OrderStep("partial-order", m.order(Language.FRENCH, partialRef, cy, List.of(new Line(2, DemoCatalog.RICE)))));
        steps.add(new PaymentStep("partial-payment", m.confirmation(FR_REF, cy, 3000, partialRef, FIVE_MINUTES)));
        steps.add(new ConfirmStep("partial-confirm", "partial-order", "partial-payment"));

        // R11: 5,600 for 5,000.
        String overRef = m.reference();
        steps.add(new OrderStep("over-order", m.order(Language.ENGLISH, overRef, dee, List.of(new Line(2, DemoCatalog.RICE)))));
        steps.add(new PaymentStep("over-payment", m.confirmation(REF, dee, 5600, overRef, FIVE_MINUTES)));

        // R2: a forwarded confirmation, no transaction id.
        String unverifiedRef = m.reference();
        // 2,800: no other open balance is 2,800 (2,000 would equal the part-paid order's balance and be suggested there too).
        List<Line> unverifiedLines = List.of(new Line(1, DemoCatalog.PALM_OIL), new Line(2, DemoCatalog.SUGAR));
        steps.add(new OrderStep("unverified-order", m.order(Language.ENGLISH, unverifiedRef, gus, unverifiedLines)));
        steps.add(new PaymentStep("unverified-payment", m.confirmation(FORWARDED, gus, DemoMessages.total(unverifiedLines), null, FIVE_MINUTES)));

        // Unread: free chat text.
        steps.add(new OrderStep("unread-order", "Hello, are you open tomorrow?"));

        // R5: paid three hours before the order was placed (the payment is recorded first).
        String earlyRef = m.reference();
        List<Line> earlyLines = List.of(new Line(1, DemoCatalog.FLOUR), new Line(2, DemoCatalog.BEANS));
        steps.add(new PaymentStep("early-payment", m.confirmation(REF, hal, DemoMessages.total(earlyLines), earlyRef, Duration.ofHours(3))));
        steps.add(new OrderStep("early-order", m.order(Language.ENGLISH, earlyRef, hal, earlyLines)));

        // R8 and R7: the reference, from another phone, under a stranger's name.
        String strangerRef = m.reference();
        List<Line> strangerLines = List.of(new Line(2, DemoCatalog.SOAP), new Line(1, DemoCatalog.RICE));
        steps.add(new OrderStep("stranger-order", m.order(Language.ENGLISH, strangerRef, ivy, strangerLines)));
        steps.add(new PaymentStep("stranger-payment", m.confirmation(FR_REF, kim, DemoMessages.total(strangerLines), strangerRef, FIVE_MINUTES)));

        // R10: one phone, two names, on two orders.
        steps.add(new OrderStep("alias-first-order", m.order(Language.ENGLISH, m.reference(), eve, List.of(new Line(1, DemoCatalog.SUGAR)))));
        steps.add(new OrderStep("alias-second-order", m.order(Language.ENGLISH, m.reference(), fay, List.of(new Line(1, DemoCatalog.BEANS)))));
        return List.copyOf(steps);
    }

    private static Payer seed(int index) {
        return new Payer(DemoNames.SEED.get(index), DemoNames.seedPhone(index + 1));
    }
}
