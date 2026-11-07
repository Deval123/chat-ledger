package dev.chatledger.ingestion;

import dev.chatledger.extraction.FailureReason;
import dev.chatledger.extraction.OrderLimits;
import dev.chatledger.extraction.ParsedOrder;
import dev.chatledger.extraction.TextKeys;
import dev.chatledger.extraction.ValidationResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a validated parse into what is stored: the initial status, the total and the priced lines. Pure: no database,
 * no clock, no text output.
 *
 * <p><b>Where each number comes from.</b> A price or total <em>written in the message</em> was read by the parser and
 * checked by the validator against the text. A price taken from the <em>catalog</em>, and a total <em>computed</em>
 * from prices, are not in the text; they are computed here with {@code Math.*Exact}, capped at
 * {@link OrderLimits#MAX_MINOR}, and are not required to appear in the message. A catalog price outside
 * {@code 1..MAX_MINOR} is ignored (treated as unknown).
 *
 * <p>A total stated in the message is never replaced by a computed one: if they disagree the order needs review.
 */
public final class OrderDecider {

    /** The statuses an order can start in. Later statuses belong to matching (task 6). */
    public enum Status { PENDING_PAYMENT, NEEDS_REVIEW, UNPARSED }

    /** A line to store; {@code unitMinor} is null when no price is known. */
    public record Line(String label, int qty, Long unitMinor) {
        @Override
        public String toString() {
            return "Line[qty=" + qty + "]";
        }
    }

    /** What to store. {@code totalMinor} is null only for NEEDS_REVIEW and UNPARSED; the currency is always the tenant's. */
    public record Draft(Status status, Long totalMinor, String currency, List<Line> lines, FailureReason reason) {
        public Draft {
            lines = List.copyOf(lines);
        }
    }

    private OrderDecider() {
    }

    /** The catalog keys the order needs: its items that have no price in the message. */
    public static Set<String> catalogKeysNeeded(ParsedOrder order) {
        Set<String> keys = new LinkedHashSet<>();
        for (ParsedOrder.OrderLine l : order.lines()) {
            if (l.unitMinor() == null && l.lineTotalMinor() == null) {
                keys.add(TextKeys.key(l.label()));
            }
        }
        return keys;
    }

    public static Draft unparsed(String tenantCurrency, FailureReason reason) {
        return new Draft(Status.UNPARSED, null, tenantCurrency, List.of(), reason);
    }

    public static Draft decide(ParsedOrder order, ValidationResult validation, Map<String, Long> catalogPrices,
                               String tenantCurrency) {
        if (validation.verdict() == ValidationResult.Verdict.REJECT) {
            return unparsed(tenantCurrency, validation.reason());
        }
        List<Line> lines = new ArrayList<>();
        boolean allKnown = true;
        for (ParsedOrder.OrderLine l : order.lines()) {
            Long unit = l.unitMinor();
            if (unit == null && l.lineTotalMinor() != null) {
                unit = l.lineTotalMinor() / l.qty();
            }
            if (unit == null) {
                Long catalog = catalogPrices.get(TextKeys.key(l.label()));
                if (catalog != null && catalog >= 1 && catalog <= OrderLimits.MAX_MINOR) {
                    unit = catalog;
                }
            }
            allKnown &= unit != null;
            lines.add(new Line(l.label(), l.qty(), unit));
        }

        Long computed = null;
        if (allKnown) {
            try {
                long sum = 0;
                for (Line l : lines) {
                    sum = Math.addExact(sum, Math.multiplyExact(l.unitMinor(), (long) l.qty()));
                }
                if (sum > OrderLimits.MAX_MINOR) {
                    return unparsed(tenantCurrency, FailureReason.AMOUNT_TOO_LARGE);
                }
                computed = sum;
            } catch (ArithmeticException overflow) {
                return unparsed(tenantCurrency, FailureReason.AMOUNT_TOO_LARGE);
            }
        }

        if (order.totalMinor() != null) {
            boolean disagrees = validation.verdict() == ValidationResult.Verdict.NEEDS_REVIEW
                    || (computed != null && !computed.equals(order.totalMinor()));
            return new Draft(disagrees ? Status.NEEDS_REVIEW : Status.PENDING_PAYMENT, order.totalMinor(), tenantCurrency,
                    lines, disagrees ? FailureReason.TOTAL_MISMATCH : null);
        }
        if (computed != null) {
            return new Draft(Status.PENDING_PAYMENT, computed, tenantCurrency, lines, null);
        }
        return new Draft(Status.NEEDS_REVIEW, null, tenantCurrency, lines, FailureReason.PRICE_UNKNOWN);
    }
}
