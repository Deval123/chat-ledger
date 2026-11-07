package dev.chatledger.extraction;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Typed parsers for the values a template's holes capture. A captured value is never used raw: it comes out of one of these
 * as a checked value, or as a {@link FailureReason}. (The hole's character set already limited what could be captured; these
 * check the shape.) Every method is a plain loop or a library call on a closed input: no pattern is built from the message.
 */
final class PaymentFieldParsers {

    /** A parsed value or the reason it was refused. */
    record Parsed<T>(T value, FailureReason failure) {
        static <T> Parsed<T> ok(T value) {
            return new Parsed<>(value, null);
        }

        static <T> Parsed<T> fail(FailureReason reason) {
            return new Parsed<>(null, reason);
        }
    }

    private PaymentFieldParsers() {
    }

    static Parsed<AmountParser.Amount> amount(String value, int scale) {
        AmountParser.Result r = AmountParser.parse(value, scale);
        return r.failure() != null ? Parsed.fail(r.failure()) : Parsed.ok(r.amount());
    }

    /** The currency word of a currency hole as an ISO code ({@code FCFA} and {@code F CFA} are {@code XAF}). */
    static Parsed<String> currency(String value) {
        String code = AmountParser.currency(value);
        return code == null ? Parsed.fail(FailureReason.INVALID_AMOUNT) : Parsed.ok(code);
    }

    static Parsed<String> transactionId(String value) {
        if (value.length() < PaymentLimits.MIN_TRANSACTION_ID || value.length() > PaymentLimits.MAX_TRANSACTION_ID
                || !asciiToken(value, true)) {
            return Parsed.fail(FailureReason.INVALID_TRANSACTION_ID);
        }
        return Parsed.ok(value);
    }

    static Parsed<String> reference(String value) {
        if (value.isEmpty() || value.length() > OrderLimits.MAX_REF || !asciiToken(value, true)) {
            return Parsed.fail(FailureReason.INVALID_REFERENCE);
        }
        return Parsed.ok(value);
    }

    static Parsed<String> name(String value) {
        String v = value.strip();
        if (v.length() < PaymentLimits.MIN_NAME || v.length() > PaymentLimits.MAX_NAME) {
            return Parsed.fail(FailureReason.INVALID_NAME);
        }
        boolean letter = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isISOControl(c)) {
                return Parsed.fail(FailureReason.INVALID_NAME);
            }
            letter |= Character.isLetter(c);
        }
        return letter ? Parsed.ok(v) : Parsed.fail(FailureReason.INVALID_NAME);
    }

    static Parsed<Phone> phone(String value) {
        Optional<Phone> phone = Phone.of(value);
        return phone.isPresent() ? Parsed.ok(phone.get()) : Parsed.fail(FailureReason.INVALID_PHONE);
    }

    static Parsed<String> last4(String value) {
        if (value.length() != 4) {
            return Parsed.fail(FailureReason.INVALID_PHONE);
        }
        for (int i = 0; i < 4; i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return Parsed.fail(FailureReason.INVALID_PHONE);
            }
        }
        return Parsed.ok(value);
    }

    /** The timestamp, read with the template's strict fixed-width format and fixed offset. */
    static Parsed<Instant> paidAt(String value, PaymentTemplate template) {
        try {
            LocalDateTime local = LocalDateTime.parse(value, template.dateFormatter());
            return Parsed.ok(local.toInstant(template.zone()));
        } catch (DateTimeParseException e) {
            return Parsed.fail(FailureReason.INVALID_DATE);   // the exception text quotes the input; it is dropped
        }
    }

    /** Letters, digits, underscore and hyphen only (and, when {@code dotsAndColons}, also dot and colon, as the database CHECK allows). */
    static boolean asciiToken(String s, boolean dotsAndColons) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    || (dotsAndColons && (c == '.' || c == ':'));
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
