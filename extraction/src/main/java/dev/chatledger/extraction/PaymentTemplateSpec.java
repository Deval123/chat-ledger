package dev.chatledger.extraction;

import java.util.List;

/**
 * A payment template as data, before it is compiled: literal text with typed holes, and the examples that prove it.
 *
 * @param id                   Lower-case letters, digits and hyphens.
 * @param provider             Provider code, upper case, digits and underscore (for example {@code PROVIDER_A}).
 * @param currency             The currency this provider pays in (three upper-case letters).
 * @param scale                Decimal places of that currency (0 to 3); used to read the examples.
 * @param transactionIdRequired {@code true} when the text carries a transaction id; {@code false} for the "forwarded" shape,
 *                             whose payments are {@code UNVERIFIED}.
 * @param zone                 The time zone of the timestamp as a fixed offset ({@code +01:00}) or {@code Z}.
 * @param dateFormat           Fixed-width numeric format built from {@code yyyy MM dd HH mm ss} and the separators {@code - / : . space}.
 * @param template             Literal text with holes written as {@code {amount}}.
 * @param examples             Messages that must match, with the exact fields they must give.
 * @param counterExamples      Messages that must not match.
 */
public record PaymentTemplateSpec(
        String id,
        String provider,
        String currency,
        int scale,
        boolean transactionIdRequired,
        String zone,
        String dateFormat,
        String template,
        List<Example> examples,
        List<String> counterExamples) {

    public PaymentTemplateSpec {
        examples = List.copyOf(examples);
        counterExamples = List.copyOf(counterExamples);
    }

    /** What a message must produce. Fields that a template does not carry are null. */
    public record Expect(Long amountMinor, String transactionId, String senderName, String senderLast4, String reference,
                         String paidAtUtc) {
    }

    public record Example(String text, Expect expect) {
    }

    @Override
    public String toString() {
        return "PaymentTemplateSpec[" + (id != null && PaymentTemplate.validId(id) ? id : "(invalid id)") + "]";
    }
}
