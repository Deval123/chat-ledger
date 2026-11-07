package dev.chatledger.extraction;

import java.time.Instant;

/**
 * The last gate on a parsed payment. It does not trust the parser: it re-checks bounds and formats and, above all, that the
 * amount is written in the message, that the currency is the tenant's, and that the timestamp is plausible.
 *
 * <p>Like the order validator it proves presence, not meaning (a price equal to the digits of a phone number would pass); the
 * template's fixed wording around the amount and the task 7 near-miss rule are the other defenses.
 */
public final class PaymentValidator {

    private PaymentValidator() {
    }

    public static ValidationResult validate(ParsedPayment p, OrderParseContext context, Instant now) {
        if (p.amountMinor() < 1 || p.amountMinor() > OrderLimits.MAX_MINOR) {
            return ValidationResult.reject(FailureReason.AMOUNT_TOO_LARGE);
        }
        if (p.currency() == null || !p.currency().equals(context.currency())) {
            return ValidationResult.reject(FailureReason.CURRENCY_MISMATCH);
        }
        if (p.transactionId() != null && PaymentFieldParsers.transactionId(p.transactionId()).failure() != null) {
            return ValidationResult.reject(FailureReason.INVALID_TRANSACTION_ID);
        }
        if (p.reference() != null && PaymentFieldParsers.reference(p.reference()).failure() != null) {
            return ValidationResult.reject(FailureReason.INVALID_REFERENCE);
        }
        if (p.senderName() != null && PaymentFieldParsers.name(p.senderName()).failure() != null) {
            return ValidationResult.reject(FailureReason.INVALID_NAME);
        }
        if (p.senderLast4() != null && PaymentFieldParsers.last4(p.senderLast4()).failure() != null) {
            return ValidationResult.reject(FailureReason.INVALID_PHONE);
        }
        if (p.senderPhone() != null && !p.senderPhone().last4().equals(p.senderLast4())) {
            return ValidationResult.reject(FailureReason.INVALID_PHONE);
        }
        String normalized = TextNormalizer.normalize(p.rawText());
        if (!SourceAmounts.contains(normalized, p.amountMinor(), context.scale())) {
            return ValidationResult.reject(FailureReason.AMOUNT_NOT_IN_SOURCE);
        }
        if (p.paidAt() == null) {
            return ValidationResult.reject(FailureReason.INVALID_DATE);
        }
        if (p.paidAt().isAfter(now.plus(PaymentLimits.MAX_FUTURE)) || p.paidAt().isBefore(now.minus(PaymentLimits.MAX_AGE))) {
            return ValidationResult.reject(FailureReason.DATE_IMPLAUSIBLE);
        }
        return ValidationResult.ok();
    }
}
