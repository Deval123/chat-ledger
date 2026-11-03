package dev.chatledger.extraction;

import java.time.Instant;

/**
 * Structured payment data extracted from a mobile money confirmation SMS.
 *
 * <p>All monetary amounts are integers in minor currency units.
 * Phone numbers are never stored raw; sender information is masked to last 4 digits.
 *
 * @param provider      Provider code as defined in the template (e.g. "PROVIDER_A").
 * @param transactionId Provider transaction identifier; null when absent (UNVERIFIED).
 * @param amountMinor   Confirmed amount in minor units.
 * @param currency      ISO currency code.
 * @param senderName    Sender name from the confirmation text.
 * @param senderLast4   Last 4 digits of the sender's phone number.
 * @param paidAt        Timestamp of the payment as stated in the confirmation.
 * @param rawText       Original SMS text, preserved for audit and cross-check.
 */
public record ParsedPayment(
        String provider,
        String transactionId,
        long amountMinor,
        String currency,
        String senderName,
        String senderLast4,
        Instant paidAt,
        String rawText
) implements ParsedMessage {}
