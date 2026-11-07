package dev.chatledger.extraction;

import java.util.ArrayList;
import java.util.List;

/**
 * The last gate on what the parser produced. It does not trust the parser: it re-checks every bound and, above all,
 * that every money amount the parser <em>read</em> is actually written in the message.
 *
 * <p><b>What this covers and what it does not.</b> The "amount appears in the source" rule applies to amounts the
 * parser read: item prices, line totals and the stated total. A total <em>computed</em> from the catalog is not in the
 * text, so it never goes through this check; the ingestion service computes it with {@code Math.*Exact}, caps it at
 * {@link OrderLimits#MAX_MINOR}, and does not require it to appear in the source.
 *
 * <p>Outcomes: {@code REJECT} (the message is kept as unparsed), {@code NEEDS_REVIEW} (the stated total disagrees with
 * the lines: the stated number is kept, never silently replaced), or {@code OK}.
 */
public final class OrderValidator {

    private OrderValidator() {
    }

    public static ValidationResult validate(ParsedOrder order, OrderParseContext context) {
        List<ParsedOrder.OrderLine> lines = order.lines();
        if (lines.isEmpty()) {
            return ValidationResult.reject(FailureReason.NO_ITEMS);
        }
        if (lines.size() > OrderLimits.MAX_ITEMS) {
            return ValidationResult.reject(FailureReason.TOO_MANY_ITEMS);
        }
        if (tooLong(order.customerName(), OrderLimits.MAX_NAME) || tooLong(order.deliveryNote(), OrderLimits.MAX_NOTE)
                || tooLong(order.reference(), OrderLimits.MAX_REF)) {
            return ValidationResult.reject(FailureReason.FIELD_TOO_LONG);
        }
        if (order.currency() != null && !order.currency().equals(context.currency())) {
            return ValidationResult.reject(FailureReason.CURRENCY_MISMATCH);
        }

        String normalized = TextNormalizer.normalize(order.rawText());
        List<Long> parserAmounts = new ArrayList<>();
        for (ParsedOrder.OrderLine line : lines) {
            if (line.qty() < OrderLimits.MIN_QTY || line.qty() > OrderLimits.MAX_QTY) {
                return ValidationResult.reject(FailureReason.INVALID_QUANTITY);
            }
            if (line.label() == null || line.label().isBlank() || line.label().length() > OrderLimits.MAX_LABEL) {
                return ValidationResult.reject(FailureReason.FIELD_TOO_LONG);
            }
            if (line.unitMinor() != null && line.lineTotalMinor() != null) {
                return ValidationResult.reject(FailureReason.INVALID_AMOUNT);
            }
            if (line.unitMinor() != null) {
                parserAmounts.add(line.unitMinor());
            }
            if (line.lineTotalMinor() != null) {
                if (line.lineTotalMinor() % line.qty() != 0) {
                    return ValidationResult.reject(FailureReason.LINE_TOTAL_NOT_DIVISIBLE);
                }
                parserAmounts.add(line.lineTotalMinor());
            }
        }
        if (order.totalMinor() != null) {
            parserAmounts.add(order.totalMinor());
        }
        for (long amount : parserAmounts) {
            if (amount < 1 || amount > OrderLimits.MAX_MINOR) {
                return ValidationResult.reject(FailureReason.AMOUNT_TOO_LARGE);
            }
            if (!SourceAmounts.contains(normalized, amount, context.scale())) {
                return ValidationResult.reject(FailureReason.AMOUNT_NOT_IN_SOURCE);
            }
        }

        if (lines.stream().allMatch(l -> l.unitMinor() != null || l.lineTotalMinor() != null)) {
            long sum = 0;
            try {
                for (ParsedOrder.OrderLine line : lines) {
                    long lineTotal = line.lineTotalMinor() != null
                            ? line.lineTotalMinor() : Math.multiplyExact(line.unitMinor(), (long) line.qty());
                    sum = Math.addExact(sum, lineTotal);
                }
            } catch (ArithmeticException overflow) {
                return ValidationResult.reject(FailureReason.AMOUNT_TOO_LARGE);
            }
            if (sum > OrderLimits.MAX_MINOR) {
                return ValidationResult.reject(FailureReason.AMOUNT_TOO_LARGE);
            }
            if (order.totalMinor() != null && sum != order.totalMinor()) {
                return ValidationResult.review(FailureReason.TOTAL_MISMATCH);
            }
        }
        return ValidationResult.ok();
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }
}
