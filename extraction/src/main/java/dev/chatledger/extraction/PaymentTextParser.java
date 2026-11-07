package dev.chatledger.extraction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a payment confirmation with the shipped templates: normalise, scan every template over the whole message, read the
 * captured values with typed parsers, and require that the templates which match agree.
 *
 * <p>This class only reads. The last gate (amount written in the message, tenant currency, plausible timestamp) is
 * {@link PaymentValidator}, applied by the pipeline, so there is exactly one place for each rule.
 *
 * <ul>
 *   <li>No template matches: {@code NO_TEMPLATE_MATCH} (the message is kept as unparsed by the caller).</li>
 *   <li>More than one template matches and they do not give the same result: {@code AMBIGUOUS_TEMPLATE}. Template order never
 *       decides money.</li>
 *   <li>A template matches but a value does not pass its typed parser: that parser's failure code.</li>
 * </ul>
 */
public final class PaymentTextParser implements MessageParser<ParsedPayment> {

    private final PaymentTemplates templates;
    private final OrderParseContext context;

    public PaymentTextParser(PaymentTemplates templates, OrderParseContext context) {
        this.templates = templates;
        this.context = context;
    }

    @Override
    public ExtractionResult<ParsedPayment> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return fail(FailureReason.EMPTY_INPUT);
        }
        if (rawText.length() > PaymentLimits.MAX_SMS_CHARS) {
            return fail(FailureReason.INPUT_TOO_LARGE);
        }
        String text = TextNormalizer.normalize(rawText);
        if (text.isEmpty()) {
            return fail(FailureReason.EMPTY_INPUT);
        }
        if (text.length() > PaymentLimits.MAX_NORMALIZED_CHARS) {
            return fail(FailureReason.INPUT_TOO_LARGE);
        }

        List<Attempt> attempts = new ArrayList<>();
        for (PaymentTemplate template : templates.all()) {
            Map<HoleType, String> values = template.match(text).orElse(null);
            if (values != null) {
                attempts.add(read(template, values, rawText));
            }
        }
        if (attempts.isEmpty()) {
            return fail(FailureReason.NO_TEMPLATE_MATCH);
        }
        if (attempts.size() == 1) {
            Attempt only = attempts.get(0);
            return only.payment != null ? new ExtractionResult.Success<>(only.payment) : fail(only.failure);
        }
        ParsedPayment first = attempts.get(0).payment;
        for (Attempt a : attempts) {
            if (a.payment == null || first == null || !first.sameResultAs(a.payment)) {
                return fail(FailureReason.AMBIGUOUS_TEMPLATE);
            }
        }
        return new ExtractionResult.Success<>(first);
    }

    private record Attempt(ParsedPayment payment, FailureReason failure) {
    }

    private Attempt read(PaymentTemplate template, Map<HoleType, String> v, String rawText) {
        PaymentFieldParsers.Parsed<AmountParser.Amount> amount = PaymentFieldParsers.amount(v.get(HoleType.AMOUNT), context.scale());
        if (amount.failure() != null) {
            return new Attempt(null, amount.failure());
        }
        if (v.containsKey(HoleType.CURRENCY)) {
            PaymentFieldParsers.Parsed<String> currency = PaymentFieldParsers.currency(v.get(HoleType.CURRENCY));
            if (currency.failure() != null) {
                return new Attempt(null, currency.failure());
            }
            if (!currency.value().equals(template.currency())) {
                return new Attempt(null, FailureReason.CURRENCY_MISMATCH);
            }
        }
        PaymentFieldParsers.Parsed<Instant> paidAt = PaymentFieldParsers.paidAt(v.get(HoleType.PAID_AT), template);
        if (paidAt.failure() != null) {
            return new Attempt(null, paidAt.failure());
        }
        String transactionId = null;
        if (v.containsKey(HoleType.TRANSACTION_ID)) {
            PaymentFieldParsers.Parsed<String> tx = PaymentFieldParsers.transactionId(v.get(HoleType.TRANSACTION_ID));
            if (tx.failure() != null) {
                return new Attempt(null, tx.failure());
            }
            transactionId = tx.value();
        }
        String name = null;
        if (v.containsKey(HoleType.SENDER_NAME)) {
            PaymentFieldParsers.Parsed<String> n = PaymentFieldParsers.name(v.get(HoleType.SENDER_NAME));
            if (n.failure() != null) {
                return new Attempt(null, n.failure());
            }
            name = n.value();
        }
        Phone phone = null;
        String last4 = null;
        if (v.containsKey(HoleType.SENDER_PHONE)) {
            PaymentFieldParsers.Parsed<Phone> p = PaymentFieldParsers.phone(v.get(HoleType.SENDER_PHONE));
            if (p.failure() != null) {
                return new Attempt(null, p.failure());
            }
            phone = p.value();
            last4 = phone.last4();
        } else if (v.containsKey(HoleType.SENDER_LAST4)) {
            PaymentFieldParsers.Parsed<String> l = PaymentFieldParsers.last4(v.get(HoleType.SENDER_LAST4));
            if (l.failure() != null) {
                return new Attempt(null, l.failure());
            }
            last4 = l.value();
        }
        String reference = null;
        if (v.containsKey(HoleType.REFERENCE)) {
            PaymentFieldParsers.Parsed<String> r = PaymentFieldParsers.reference(v.get(HoleType.REFERENCE));
            if (r.failure() != null) {
                return new Attempt(null, r.failure());
            }
            reference = r.value();
        }
        return new Attempt(new ParsedPayment(template.id(), template.provider(), transactionId, amount.value().minor(),
                template.currency(), name, phone, last4, reference, paidAt.value(), rawText), null);
    }

    private static ExtractionResult<ParsedPayment> fail(FailureReason reason) {
        return new ExtractionResult.Failure<>(reason);
    }
}
