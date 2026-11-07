package dev.chatledger.extraction;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A compiled payment template: literal text with typed holes, and a scanner that matches it against a whole message.
 *
 * <h2>How the scan works (and why it cannot be slow)</h2>
 * The template is a list of segments: literals and holes, with a literal between any two holes. Matching walks the message
 * once from the start:
 * <ul>
 *   <li>a <b>literal</b> must be there exactly, at the current position;</li>
 *   <li>a <b>hole</b> consumes characters its type allows, up to its maximum length, then gives back any trailing spaces
 *       (they belong to the literal that follows); it must hold at least its minimum length and may not start with a space.</li>
 * </ul>
 * and the message must end exactly where the template ends. There is no search, no backtracking and no choice point: each
 * message character is looked at a constant number of times, so the work is linear in the message and the template, whatever
 * the message contains. The step count is exposed for tests so this is a measured fact, not an assumption.
 *
 * <p>Because a hole stops at the first character its type does not allow, a template is only accepted if the first character
 * of every literal that follows a hole is <em>not</em> one the hole allows (a space is the one exception, handled by the
 * give-back rule). That makes the end of every hole unambiguous.
 *
 * <p>The cost, named: this is less flexible than a pattern. There are no optional parts and no alternatives inside one
 * template; a provider text that sometimes has a reference is written as two templates.
 */
public final class PaymentTemplate {

    /** A piece of a template. */
    public sealed interface Segment permits Literal, Hole {
    }

    public record Literal(String text) implements Segment {
    }

    public record Hole(HoleType type) implements Segment {
    }

    /** The values a message gave for each hole, and how many steps the scan took. */
    public record Scan(java.util.Map<HoleType, String> values, long steps) {
        public boolean matched() {
            return values != null;
        }
    }

    private static final Set<Character> DATE_SEPARATORS = Set.of('-', '/', ':', '.', ' ');

    private final String id;
    private final String provider;
    private final String currency;
    private final boolean transactionIdRequired;
    private final ZoneOffset zone;
    private final DateTimeFormatter dateFormatter;
    private final List<Segment> segments;
    private final Set<HoleType> holes;

    private PaymentTemplate(String id, String provider, String currency, boolean transactionIdRequired, ZoneOffset zone,
                            DateTimeFormatter dateFormatter, List<Segment> segments, Set<HoleType> holes) {
        this.id = id;
        this.provider = provider;
        this.currency = currency;
        this.transactionIdRequired = transactionIdRequired;
        this.zone = zone;
        this.dateFormatter = dateFormatter;
        this.segments = List.copyOf(segments);
        this.holes = Set.copyOf(holes);
    }

    public String id() {
        return id;
    }

    public String provider() {
        return provider;
    }

    public String currency() {
        return currency;
    }

    public boolean transactionIdRequired() {
        return transactionIdRequired;
    }

    public ZoneOffset zone() {
        return zone;
    }

    DateTimeFormatter dateFormatter() {
        return dateFormatter;
    }

    public Set<HoleType> holes() {
        return holes;
    }

    // ── Compile ─────────────────────────────────────────────────────────────

    static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean validUpper(String s, int max, boolean allowDigitsAndUnderscore) {
        if (s == null || s.isEmpty() || s.length() > max) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (allowDigitsAndUnderscore && ((c >= '0' && c <= '9') || c == '_'));
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Checks every rule and builds the template, or throws a {@link TemplateException} carrying only a code. */
    public static PaymentTemplate compile(PaymentTemplateSpec spec) {
        String id = spec.id();
        if (!validId(id)) {
            throw new TemplateException(null, TemplateProblem.BAD_ID);
        }
        if (!validUpper(spec.provider(), 32, true)) {
            throw new TemplateException(id, TemplateProblem.BAD_PROVIDER);
        }
        if (!validUpper(spec.currency(), 3, false) || spec.currency().length() != 3) {
            throw new TemplateException(id, TemplateProblem.BAD_CURRENCY);
        }
        if (spec.scale() < 0 || spec.scale() > 3) {
            throw new TemplateException(id, TemplateProblem.BAD_SCALE);
        }
        ZoneOffset zone = parseZone(spec.zone());
        if (zone == null) {
            throw new TemplateException(id, TemplateProblem.BAD_ZONE);
        }
        DateFormat date = parseDateFormat(spec.dateFormat());
        if (date == null) {
            throw new TemplateException(id, TemplateProblem.BAD_DATE_FORMAT);
        }

        String text = spec.template();
        if (text == null || text.isEmpty()) {
            throw new TemplateException(id, TemplateProblem.EMPTY_TEMPLATE);
        }
        if (text.length() > PaymentLimits.MAX_TEMPLATE_CHARS) {
            throw new TemplateException(id, TemplateProblem.TEMPLATE_TOO_LONG);
        }

        List<Segment> segments = new ArrayList<>();
        EnumSet<HoleType> seen = EnumSet.noneOf(HoleType.class);
        StringBuilder literal = new StringBuilder();
        StringBuilder normalisedProbe = new StringBuilder();
        int literalChars = 0;
        boolean lastWasHole = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '}') {
                throw new TemplateException(id, TemplateProblem.STRAY_BRACE);
            }
            if (c != '{') {
                literal.append(c);
                normalisedProbe.append(c);
                literalChars++;
                lastWasHole = false;
                continue;
            }
            int close = text.indexOf('}', i);
            if (close < 0 || close - i > 20) {
                throw new TemplateException(id, TemplateProblem.UNTERMINATED_HOLE);
            }
            HoleType type = HoleType.byName(text.substring(i + 1, close))
                    .orElseThrow(() -> new TemplateException(id, TemplateProblem.UNKNOWN_HOLE));
            if (!seen.add(type)) {
                throw new TemplateException(id, TemplateProblem.DUPLICATE_HOLE);
            }
            if (seen.size() > PaymentLimits.MAX_HOLES) {
                throw new TemplateException(id, TemplateProblem.TOO_MANY_HOLES);
            }
            if (literal.length() > 0) {
                segments.add(new Literal(literal.toString()));
                literal.setLength(0);
            } else if (lastWasHole) {
                throw new TemplateException(id, TemplateProblem.ADJACENT_HOLES);
            }
            segments.add(new Hole(type));
            normalisedProbe.append('X');
            lastWasHole = true;
            i = close;
        }
        if (literal.length() > 0) {
            segments.add(new Literal(literal.toString()));
        }
        if (literalChars < PaymentLimits.MIN_LITERAL_CHARS) {
            throw new TemplateException(id, TemplateProblem.TOO_FEW_LITERALS);
        }
        // The scanner runs on normalised text, so the template must already be written in normalised form.
        String probe = normalisedProbe.toString();
        if (!probe.equals(TextNormalizer.normalize(probe))) {
            throw new TemplateException(id, TemplateProblem.NOT_NORMALISED);
        }

        // Delimiter rule: a hole stops at the first character its type does not allow, so what follows it must start with a
        // character the hole does not allow. If the hole allows a space, the scan would run through spaces, so the first
        // non-space character of the next literal (or, between two holes, the whole character set of the next hole) must not
        // be one the hole allows either.
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (!(segments.get(i) instanceof Hole h) || !(segments.get(i + 1) instanceof Literal l)) {
                continue;
            }
            String t = l.text();
            int k = 0;
            while (k < t.length() && t.charAt(k) == ' ') {
                k++;
            }
            boolean spaceAllowed = h.type().allows(' ');
            if (k == t.length()) {
                if (spaceAllowed && i + 2 < segments.size() && segments.get(i + 2) instanceof Hole next
                        && overlap(h.type(), next.type())) {
                    throw new TemplateException(id, TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);
                }
            } else if ((k == 0 || spaceAllowed) && h.type().allows(t.charAt(k))) {
                throw new TemplateException(id, TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);
            }
        }

        if (!seen.contains(HoleType.AMOUNT) || !seen.contains(HoleType.PAID_AT)) {
            throw new TemplateException(id, TemplateProblem.MISSING_REQUIRED_HOLE);
        }
        if (spec.transactionIdRequired() && !seen.contains(HoleType.TRANSACTION_ID)) {
            throw new TemplateException(id, TemplateProblem.MISSING_REQUIRED_HOLE);
        }
        if (!spec.transactionIdRequired() && seen.contains(HoleType.TRANSACTION_ID)) {
            throw new TemplateException(id, TemplateProblem.TRANSACTION_ID_NOT_ALLOWED);
        }
        if (seen.contains(HoleType.SENDER_PHONE) && seen.contains(HoleType.SENDER_LAST4)) {
            throw new TemplateException(id, TemplateProblem.SENDER_CONFLICT);
        }
        return new PaymentTemplate(id, spec.provider(), spec.currency(), spec.transactionIdRequired(), zone,
                date.formatter(), segments, seen);
    }

    /** True if some character other than a space is allowed by both hole types (checked over the Latin range). */
    private static boolean overlap(HoleType a, HoleType b) {
        for (char c = 1; c < 0x300; c++) {
            if (c != ' ' && a.allows(c) && b.allows(c)) {
                return true;
            }
        }
        return false;
    }

    private static ZoneOffset parseZone(String zone) {
        if (zone == null) {
            return null;
        }
        if (zone.equals("Z")) {
            return ZoneOffset.UTC;
        }
        if (zone.length() != 6 || (zone.charAt(0) != '+' && zone.charAt(0) != '-') || zone.charAt(3) != ':') {
            return null;
        }
        for (int i : new int[]{1, 2, 4, 5}) {
            if (zone.charAt(i) < '0' || zone.charAt(i) > '9') {
                return null;
            }
        }
        int hours = (zone.charAt(1) - '0') * 10 + (zone.charAt(2) - '0');
        int minutes = (zone.charAt(4) - '0') * 10 + (zone.charAt(5) - '0');
        if (hours > 14 || minutes > 59) {
            return null;
        }
        int sign = zone.charAt(0) == '-' ? -1 : 1;
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes);
    }

    private record DateFormat(DateTimeFormatter formatter) {
    }

    /** Fixed-width numeric formats only: tokens yyyy MM dd HH mm ss (each at most once, yyyy MM dd HH mm required) and one-character separators. */
    private static DateFormat parseDateFormat(String format) {
        if (format == null || format.isEmpty() || format.length() > 40) {
            return null;
        }
        StringBuilder formatText = new StringBuilder();
        Set<String> tokens = new java.util.HashSet<>();
        int i = 0;
        while (i < format.length()) {
            String token = null;
            for (String t : new String[]{"yyyy", "MM", "dd", "HH", "mm", "ss"}) {
                if (format.startsWith(t, i)) {
                    token = t;
                    break;
                }
            }
            if (token != null) {
                if (!tokens.add(token)) {
                    return null;
                }
                formatText.append(token.equals("yyyy") ? "uuuu" : token);
                i += token.length();
            } else if (DATE_SEPARATORS.contains(format.charAt(i))) {
                formatText.append(format.charAt(i));
                i++;
            } else {
                return null;
            }
        }
        for (String required : new String[]{"yyyy", "MM", "dd", "HH", "mm"}) {
            if (!tokens.contains(required)) {
                return null;
            }
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(formatText.toString(), Locale.ROOT)
                .withResolverStyle(ResolverStyle.STRICT);
        return new DateFormat(formatter);
    }

    // ── Scan ────────────────────────────────────────────────────────────────

    /** The values for each hole if the whole message matches, or empty. */
    public Optional<java.util.Map<HoleType, String>> match(String text) {
        Scan scan = scan(text);
        return scan.matched() ? Optional.of(scan.values()) : Optional.empty();
    }

    /** The scan with its step count (every message character examined counts one step). For tests and measurement. */
    public Scan scan(String text) {
        long steps = 0;
        int pos = 0;
        int n = text.length();
        EnumMap<HoleType, String> values = new EnumMap<>(HoleType.class);
        for (Segment segment : segments) {
            if (segment instanceof Literal literal) {
                String lit = literal.text();
                if (n - pos < lit.length()) {
                    return new Scan(null, steps);
                }
                for (int k = 0; k < lit.length(); k++) {
                    steps++;
                    if (text.charAt(pos + k) != lit.charAt(k)) {
                        return new Scan(null, steps);
                    }
                }
                pos += lit.length();
            } else {
                HoleType type = ((Hole) segment).type();
                int start = pos;
                int limit = Math.min(n, pos + type.max());
                int end = start;
                while (end < limit && type.allows(text.charAt(end))) {
                    steps++;
                    end++;
                }
                while (end > start && text.charAt(end - 1) == ' ') {   // give trailing spaces back to the next literal
                    steps++;
                    end--;
                }
                if (end - start < type.min() || (end > start && text.charAt(start) == ' ')) {
                    return new Scan(null, steps);
                }
                values.put(type, text.substring(start, end));
                pos = end;
            }
        }
        if (pos != n) {
            return new Scan(null, steps);
        }
        return new Scan(values, steps);
    }
}
