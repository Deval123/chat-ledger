package dev.chatledger.demo;

import dev.chatledger.extraction.HoleType;
import dev.chatledger.extraction.PaymentTemplateSpec;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Writes the demo's order messages and payment confirmations.
 *
 * <p>A confirmation is built from a loaded template spec: its literal text, its holes, its currency, its zone and its date format. It is
 * never a second copy of a template, so a template that changes cannot quietly turn demo payments into unread ones. Every time is "now
 * minus an age" with the age between 0 and {@link #MAX_AGE}: never ahead of now, and far inside the 35-day plausibility window
 * ({@code PaymentLimits}). Minutes are the finest unit the formats carry, so the written time is cut down to the minute, never later than
 * now.
 */
public final class DemoMessages {

    /** The oldest payment the demo writes (scenario 7, "late payment", is four days old). */
    public static final Duration MAX_AGE = Duration.ofDays(4);

    /** Characters of references and transaction ids: no 0, O, 1, I, so a reference read aloud is not misread. */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public enum Language { ENGLISH, FRENCH }

    /** An order line: a quantity of one catalog item, named in the order's language, without a price. */
    public record Line(int quantity, DemoCatalog.Item item) {
        public Line {
            if (quantity < 1 || quantity > 99) {
                throw new IllegalArgumentException("quantity out of range");
            }
        }

        long totalMinor() {
            return quantity * item.unitMinor();
        }
    }

    /** Who pays: a name and either a full phone or, for a forwarded confirmation, only its last four digits. */
    public record Payer(String name, String phone) {
        public Payer {
            if (!DemoNames.allowed(name) || !DemoNames.syntheticPhone(phone)) {
                throw new IllegalArgumentException("the demo writes fictional names and synthetic phones only");
            }
        }

        String last4() {
            String digits = phone.replaceAll("[^0-9]", "");
            return digits.substring(digits.length() - 4);
        }
    }

    private final Map<String, PaymentTemplateSpec> templates = new LinkedHashMap<>();
    private final Clock clock;
    private final RandomGenerator random;

    public DemoMessages(List<PaymentTemplateSpec> specs, Clock clock, RandomGenerator random) {
        for (PaymentTemplateSpec spec : specs) {
            templates.put(spec.id(), spec);
        }
        this.clock = clock;
        this.random = random;
    }

    public Clock clock() {
        return clock;
    }

    /** An order code: {@code D-} and four characters. */
    public String reference() {
        return "D-" + token(4);
    }

    /** A transaction id: {@code DT} and eight characters (6 to 40 are accepted). */
    public String transactionId() {
        return "DT" + token(8);
    }

    public String freshPhone() {
        return DemoNames.freshPhone(random);
    }

    private String token(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** The total the catalog gives these lines. */
    public static long total(List<Line> lines) {
        long sum = 0;
        for (Line line : lines) {
            sum = Math.addExact(sum, line.totalMinor());
        }
        return sum;
    }

    /** An order in labelled lines, English or French; the reference may be null. Items carry no price: the catalog has them. */
    public String order(Language language, String reference, Payer customer, List<Line> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an order has at least one line");
        }
        boolean fr = language == Language.FRENCH;
        StringBuilder sb = new StringBuilder();
        if (reference != null) {
            sb.append(fr ? "Commande: " : "Order: ").append(reference).append('\n');
        }
        sb.append(fr ? "Nom: " : "Name: ").append(customer.name()).append('\n');
        sb.append(fr ? "Tél: " : "Phone: ").append(customer.phone());
        for (Line line : lines) {
            sb.append('\n').append(line.quantity()).append(" x ").append(fr ? line.item().french() : line.item().english());
        }
        return sb.toString();
    }

    /** An order on one line, English, without a reference: {@code 2 x sugar 1kg, 1 x flour 2kg, from NAME, phone PHONE}. */
    public String oneLineOrder(Payer customer, List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line line : lines) {
            sb.append(line.quantity()).append(" x ").append(line.item().english()).append(", ");
        }
        return sb.append("from ").append(customer.name()).append(", phone ").append(customer.phone()).toString();
    }

    /**
     * A confirmation in the shape of template {@code templateId}, paid {@code age} ago. A hole the template does not have is not written; a
     * hole it has must have a value here (the reference, for a template with {@code {reference}}).
     */
    public String confirmation(String templateId, Payer payer, long amountMinor, String reference, Duration age) {
        PaymentTemplateSpec spec = templates.get(templateId);
        if (spec == null) {
            throw new IllegalArgumentException("unknown template");
        }
        if (spec.scale() != 0) {
            throw new IllegalArgumentException("the demo writes amounts in a currency without decimals");
        }
        if (age.isNegative() || age.compareTo(MAX_AGE) > 0) {
            throw new IllegalArgumentException("a demo payment is between now and four days old");
        }
        if (amountMinor < 1) {
            throw new IllegalArgumentException("amount out of range");
        }
        Map<HoleType, String> values = new EnumMap<>(HoleType.class);
        values.put(HoleType.AMOUNT, Long.toString(amountMinor));
        values.put(HoleType.CURRENCY, spec.currency());
        values.put(HoleType.SENDER_NAME, payer.name());
        values.put(HoleType.SENDER_PHONE, payer.phone());
        values.put(HoleType.SENDER_LAST4, payer.last4());
        values.put(HoleType.TRANSACTION_ID, transactionId());
        if (reference != null) {
            values.put(HoleType.REFERENCE, reference);
        }
        values.put(HoleType.PAID_AT, DateTimeFormatter.ofPattern(spec.dateFormat())
                .format(clock.instant().minus(age).atOffset(ZoneOffset.of(spec.zone()))));
        return fill(spec.template(), values);
    }

    /** True if the template has a {@code {reference}} hole. */
    public boolean takesReference(String templateId) {
        PaymentTemplateSpec spec = templates.get(templateId);
        return spec != null && spec.template().contains("{" + HoleType.REFERENCE.fieldName() + "}");
    }

    static String fill(String template, Map<HoleType, String> values) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) {
                out.append(template, i, template.length());
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                throw new IllegalArgumentException("an unclosed hole");
            }
            out.append(template, i, open);
            HoleType hole = HoleType.byName(template.substring(open + 1, close))
                    .orElseThrow(() -> new IllegalArgumentException("an unknown hole"));
            String value = values.get(hole);
            if (value == null) {
                throw new IllegalArgumentException("no value for a hole of the template");
            }
            if (value.length() < hole.min() || value.length() > hole.max() || !value.chars().allMatch(c -> hole.allows((char) c))) {
                throw new IllegalArgumentException("a value the template's hole would not read");
            }
            out.append(value);
            i = close + 1;
        }
        return out.toString();
    }
}
