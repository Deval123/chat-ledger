package dev.chatledger.extraction;

import java.time.Instant;
import java.util.Objects;

/**
 * Structured payment data read from a mobile money confirmation text.
 *
 * <p>All money amounts are integers in minor units. The sender's phone is a {@link Phone}: it prints masked and only gives out
 * its last four digits and a keyed hash. {@code toString} prints flags only, so a name, a number or the text can never reach a
 * log through this record.
 *
 * @param templateId    The template that matched (shipped data, safe to log).
 * @param provider      Provider code from the template (for example {@code PROVIDER_A}); not read from the message.
 * @param transactionId The provider's transaction id, or null for a template that declares none (the payment is then unverified).
 * @param amountMinor   Amount in minor units.
 * @param currency      The currency of the template (an ISO code).
 * @param senderName    The sender's name as written (may be null).
 * @param senderPhone   The sender's phone in canonical form, if the text carried a full number (may be null).
 * @param senderLast4   The last four digits of the sender's number (from the phone, or from a last-4 hole), or null.
 * @param reference     The order code the payer wrote in the transfer note, or null.
 * @param paidAt        The time of the payment as stated in the message.
 * @param rawText       The original text, exactly as received.
 */
public record ParsedPayment(
        String templateId,
        String provider,
        String transactionId,
        long amountMinor,
        String currency,
        String senderName,
        Phone senderPhone,
        String senderLast4,
        String reference,
        Instant paidAt,
        String rawText
) implements ParsedMessage {

    /** True if everything the message says is the same (the template that read it may differ). */
    public boolean sameResultAs(ParsedPayment other) {
        return amountMinor == other.amountMinor
                && Objects.equals(provider, other.provider)
                && Objects.equals(transactionId, other.transactionId)
                && Objects.equals(currency, other.currency)
                && Objects.equals(senderName, other.senderName)
                && Objects.equals(senderPhone, other.senderPhone)
                && Objects.equals(senderLast4, other.senderLast4)
                && Objects.equals(reference, other.reference)
                && Objects.equals(paidAt, other.paidAt);
    }

    @Override
    public String toString() {
        return "ParsedPayment[template=" + templateId + ", hasTransactionId=" + (transactionId != null)
                + ", hasSender=" + (senderName != null) + ", hasPhone=" + (senderPhone != null)
                + ", hasReference=" + (reference != null) + "]";
    }
}
