package dev.chatledger.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads an order from message text. Deterministic and strict: it understands two shapes and nothing else, and
 * anything it does not understand fails (the caller then keeps the message as unparsed) instead of being guessed at.
 *
 * <pre>
 * Labelled lines                       One line
 * Order: A-1042                        2 x tomato box, 1 x rice 5kg, total 6000 XAF, from Ana K.
 * Name: Ana K.                         2 x tomates, 1 x riz 5kg, total 6000 FCFA, de Aicha N.
 * Phone: +999 000 000 1234
 * 2 x tomato box @ 1500                An item is  QTY x NAME  optionally followed by  @ UNIT-PRICE
 * 1 x rice 5kg = 3000                  or  = LINE-TOTAL.  French labels: Commande, Nom, Telephone, Montant,
 * Total: 6000 XAF                      Livraison.
 * Deliver: gate near the market
 * </pre>
 *
 * <p>How it stays safe on hostile text: it is a single pass over at most {@link OrderLimits#MAX_NORMALIZED_CHARS}
 * characters with no backtracking; no regular expression is built from the message; the message is never used as
 * a format string, a script, a class name or a template; numbers are read digit by digit with a digit cap and
 * {@code Math.*Exact} arithmetic; every list and field has a hard cap ({@link OrderLimits}); and a failure is an
 * enum code that carries no message text.
 */
public final class OrderTextParser implements MessageParser<ParsedOrder> {

    private enum Kind { ORDER, NAME, PHONE, TOTAL, DELIVERY }

    /** Thrown inside the parser only; carries a code, never text from the message. */
    private static final class Reject extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient FailureReason reason;

        Reject(FailureReason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }

    private static final class Fields {
        String reference;
        String name;
        Phone phone;
        Long total;
        String currency;
        String note;
        final List<ParsedOrder.OrderLine> items = new ArrayList<>();
    }

    private final OrderParseContext context;

    public OrderTextParser(OrderParseContext context) {
        this.context = context;
    }

    @Override
    public ExtractionResult<ParsedOrder> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return fail(FailureReason.EMPTY_INPUT);
        }
        if (rawText.length() > OrderLimits.MAX_INPUT_CHARS) {
            return fail(FailureReason.INPUT_TOO_LARGE);
        }
        String text = TextNormalizer.normalize(rawText);
        if (text.isEmpty()) {
            return fail(FailureReason.EMPTY_INPUT);
        }
        if (text.length() > OrderLimits.MAX_NORMALIZED_CHARS) {
            return fail(FailureReason.INPUT_TOO_LARGE);
        }
        String[] lines = text.split("\n", -1);
        if (lines.length > OrderLimits.MAX_LINES) {
            return fail(FailureReason.TOO_MANY_LINES);
        }

        Fields fields = new Fields();
        try {
            for (String line : lines) {
                if (readLabelLine(line, fields)) {
                    continue;
                }
                if (lines.length == 1) {
                    readOneLine(line, fields);
                } else if (startsWithDigit(line)) {
                    readItem(line, fields);
                } else {
                    throw new Reject(FailureReason.UNRECOGNIZED_LINE);
                }
            }
            if (fields.items.isEmpty()) {
                throw new Reject(FailureReason.NO_ITEMS);
            }
        } catch (Reject r) {
            return fail(r.reason);
        }
        return new ExtractionResult.Success<>(new ParsedOrder(fields.reference, fields.name, fields.phone,
                fields.items, fields.total, fields.currency, fields.note, rawText));
    }

    private static ExtractionResult<ParsedOrder> fail(FailureReason reason) {
        return new ExtractionResult.Failure<>(reason);
    }

    // ── Labelled lines ──────────────────────────────────────────────────────

    /** True if the line is "Label: value" with a known label (and it was applied). */
    private boolean readLabelLine(String line, Fields f) {
        int colon = line.indexOf(':');
        if (colon <= 0 || colon > 40) {
            return false;
        }
        Kind kind = kindOf(TextKeys.key(line.substring(0, colon)));
        if (kind == null) {
            return false;
        }
        apply(kind, line.substring(colon + 1).strip(), f);
        return true;
    }

    private static Kind kindOf(String key) {
        if (OrderLabels.ORDER.contains(key)) {
            return Kind.ORDER;
        }
        if (OrderLabels.NAME.contains(key) || OrderLabels.FROM.contains(key)) {
            return Kind.NAME;
        }
        if (OrderLabels.PHONE.contains(key)) {
            return Kind.PHONE;
        }
        if (OrderLabels.TOTAL.contains(key)) {
            return Kind.TOTAL;
        }
        if (OrderLabels.DELIVERY.contains(key)) {
            return Kind.DELIVERY;
        }
        return null;
    }

    private void apply(Kind kind, String value, Fields f) {
        if (value.isEmpty()) {
            throw new Reject(FailureReason.INVALID_FIELD);
        }
        switch (kind) {
            case ORDER -> {
                once(f.reference);
                f.reference = reference(value);
            }
            case NAME -> {
                once(f.name);
                f.name = text(value, OrderLimits.MAX_NAME);
            }
            case PHONE -> {
                once(f.phone);
                Optional<Phone> phone = Phone.of(value);
                if (phone.isEmpty()) {
                    throw new Reject(FailureReason.INVALID_PHONE);
                }
                f.phone = phone.get();
            }
            case TOTAL -> {
                once(f.total);
                AmountParser.Amount amount = amount(value);
                f.total = amount.minor();
                currency(f, amount.currency());
            }
            case DELIVERY -> {
                once(f.note);
                f.note = text(value, OrderLimits.MAX_NOTE);
            }
        }
    }

    private static void once(Object existing) {
        if (existing != null) {
            throw new Reject(FailureReason.DUPLICATE_FIELD);
        }
    }

    private static String reference(String value) {
        String v = value.startsWith("#") ? value.substring(1).strip() : value;
        if (v.isEmpty() || v.length() > OrderLimits.MAX_REF) {
            throw new Reject(v.isEmpty() ? FailureReason.INVALID_FIELD : FailureReason.FIELD_TOO_LONG);
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == ':' || c == '-';
            if (!ok) {
                throw new Reject(FailureReason.INVALID_FIELD);
            }
        }
        return v;
    }

    private static String text(String value, int max) {
        String v = value.strip();
        if (v.isEmpty()) {
            throw new Reject(FailureReason.INVALID_FIELD);
        }
        if (v.length() > max) {
            throw new Reject(FailureReason.FIELD_TOO_LONG);
        }
        return v;
    }

    private AmountParser.Amount amount(String value) {
        AmountParser.Result r = AmountParser.parse(value, context.scale());
        if (r.failure() != null) {
            throw new Reject(r.failure());
        }
        return r.amount();
    }

    private static void currency(Fields f, String stated) {
        if (stated == null) {
            return;
        }
        if (f.currency == null) {
            f.currency = stated;
        } else if (!f.currency.equals(stated)) {
            throw new Reject(FailureReason.CURRENCY_MISMATCH);
        }
    }

    // ── Item lines ──────────────────────────────────────────────────────────

    private static boolean startsWithDigit(String s) {
        return !s.isEmpty() && s.charAt(0) >= '0' && s.charAt(0) <= '9';
    }

    /** {@code QTY x NAME [@ UNIT | = LINE-TOTAL]} */
    private void readItem(String s, Fields f) {
        int n = s.length();
        int i = 0;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        if (i == 0) {
            throw new Reject(FailureReason.UNRECOGNIZED_LINE);
        }
        if (i > 4) {
            throw new Reject(FailureReason.INVALID_QUANTITY);
        }
        int qty = Integer.parseInt(s.substring(0, i));
        if (qty < OrderLimits.MIN_QTY || qty > OrderLimits.MAX_QTY) {
            throw new Reject(FailureReason.INVALID_QUANTITY);
        }
        while (i < n && s.charAt(i) == ' ') {
            i++;
        }
        if (i >= n || OrderLabels.TIMES.indexOf(s.charAt(i)) < 0) {
            throw new Reject(FailureReason.UNRECOGNIZED_LINE);
        }
        i++;
        if (i >= n || s.charAt(i) != ' ') {
            throw new Reject(FailureReason.UNRECOGNIZED_LINE);
        }
        String rest = s.substring(i).strip();
        int at = rest.indexOf('@');
        int eq = rest.indexOf('=');
        int cut = at < 0 ? eq : (eq < 0 ? at : Math.min(at, eq));
        String label = (cut < 0 ? rest : rest.substring(0, cut)).strip();
        checkLabel(label);

        Long unit = null;
        Long lineTotal = null;
        if (cut >= 0) {
            AmountParser.Amount amount = amount(rest.substring(cut + 1));
            currency(f, amount.currency());
            if (rest.charAt(cut) == '@') {
                unit = amount.minor();
            } else {
                if (amount.minor() % qty != 0) {
                    throw new Reject(FailureReason.LINE_TOTAL_NOT_DIVISIBLE);
                }
                lineTotal = amount.minor();
            }
        }
        if (f.items.size() >= OrderLimits.MAX_ITEMS) {
            throw new Reject(FailureReason.TOO_MANY_ITEMS);
        }
        f.items.add(new ParsedOrder.OrderLine(label, qty, unit, lineTotal));
    }

    private static void checkLabel(String label) {
        if (label.isEmpty()) {
            throw new Reject(FailureReason.INVALID_FIELD);
        }
        if (label.length() > OrderLimits.MAX_LABEL) {
            throw new Reject(FailureReason.FIELD_TOO_LONG);
        }
        boolean letter = false;
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                letter = true;
            }
        }
        if (!letter) {
            throw new Reject(FailureReason.INVALID_FIELD);
        }
    }

    // ── One-line format ─────────────────────────────────────────────────────

    /**
     * Segments are separated by ";" or by a comma followed by a space (so "6,000" stays whole). A delivery note takes the
     * rest of the line, commas included.
     */
    private void readOneLine(String line, Fields f) {
        int start = 0;
        int n = line.length();
        int i = 0;
        while (start <= n) {
            boolean split = i >= n || line.charAt(i) == ';'
                    || (line.charAt(i) == ',' && (i + 1 >= n || line.charAt(i + 1) == ' '));
            if (!split) {
                i++;
                continue;
            }
            String segment = line.substring(start, i).strip();
            if (!segment.isEmpty()) {
                if (startsWithDigit(segment)) {
                    readItem(segment, f);
                } else if (readKeywordSegment(line, start, i, f)) {
                    return;   // a delivery note consumed the rest of the line
                }
            }
            start = i + 1;
            i = start;
            if (start > n) {
                break;
            }
        }
    }

    /** "total 6000", "from Ana", "phone ...", "deliver ...". Returns true if the rest of the line was consumed. */
    private boolean readKeywordSegment(String line, int start, int end, Fields f) {
        String segment = line.substring(start, end);
        int lead = 0;
        while (lead < segment.length() && segment.charAt(lead) == ' ') {
            lead++;
        }
        String body = segment.substring(lead);
        int firstSpace = body.indexOf(' ');
        if (firstSpace < 0) {
            throw new Reject(FailureReason.UNRECOGNIZED_LINE);
        }
        int secondSpace = body.indexOf(' ', firstSpace + 1);

        Kind kind = null;
        int restFrom = -1;
        if (secondSpace > 0) {
            Kind two = kindOf(TextKeys.key(stripColon(body.substring(0, secondSpace))));
            if (two != null) {
                kind = two;
                restFrom = secondSpace + 1;
            }
        }
        if (kind == null) {
            kind = kindOf(TextKeys.key(stripColon(body.substring(0, firstSpace))));
            restFrom = firstSpace + 1;
        }
        if (kind == null) {
            throw new Reject(FailureReason.UNRECOGNIZED_LINE);
        }
        if (kind == Kind.DELIVERY) {
            apply(kind, line.substring(start + lead + restFrom).strip(), f);
            return true;
        }
        apply(kind, body.substring(restFrom).strip(), f);
        return false;
    }

    private static String stripColon(String word) {
        String w = word.strip();
        return w.endsWith(":") ? w.substring(0, w.length() - 1) : w;
    }
}
