package dev.chatledger.ingestion;

import dev.chatledger.extraction.FailureReason;
import dev.chatledger.extraction.ParsedPayment;

/**
 * The status a parsed and validated payment starts in. Pure: no database, no text output.
 *
 * <ul>
 *   <li>{@code RECEIVED}: the text carried a provider transaction id.</li>
 *   <li>{@code UNVERIFIED}: the template declares no transaction id (a forwarded text). Such a payment is never auto-matched.</li>
 *   <li>{@code UNPARSED}: no template matched, or the text did not pass a check. The text is kept, nothing is read from it.</li>
 * </ul>
 * {@code DUPLICATE} is decided later by the service, which is the only place that can see the database.
 */
public final class PaymentDecider {

    public enum Status { RECEIVED, UNVERIFIED, UNPARSED }

    /** What to store. {@code parsed} is null for an unparsed message; {@code reason} is set for unparsed only. */
    public record Draft(Status status, ParsedPayment parsed, FailureReason reason) {
    }

    private PaymentDecider() {
    }

    public static Draft unparsed(FailureReason reason) {
        return new Draft(Status.UNPARSED, null, reason);
    }

    public static Draft decide(ParsedPayment parsed) {
        return new Draft(parsed.transactionId() == null ? Status.UNVERIFIED : Status.RECEIVED, parsed, null);
    }
}
