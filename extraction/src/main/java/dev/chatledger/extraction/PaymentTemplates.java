package dev.chatledger.extraction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of active payment templates, built only from specs that pass every gate. {@link #of} compiles each spec and then
 * proves the whole set with the examples the specs carry: every example must parse to exactly the stated fields, no
 * counter-example may match, and no example of one template may match another. Any failure throws a {@link TemplateException}
 * (a code and an id, never text), and the caller must treat that as fatal: the application does not start with a template that
 * has not been proven.
 */
public final class PaymentTemplates {

    /** The fixed "now" the examples are checked against, so they do not expire as time passes. */
    public static final Instant SELF_TEST_NOW = Instant.parse("2026-11-03T12:00:00Z");

    private final List<PaymentTemplate> templates;

    private PaymentTemplates(List<PaymentTemplate> templates) {
        this.templates = List.copyOf(templates);
    }

    /**
     * A set that has NOT been proven by examples. Package-private on purpose: only the tests of the runtime ambiguity rule need
     * overlapping templates, which {@link #of} would refuse. No production code can build an unproven set.
     */
    static PaymentTemplates unchecked(List<PaymentTemplate> templates) {
        return new PaymentTemplates(templates);
    }

    public List<PaymentTemplate> all() {
        return templates;
    }

    public static PaymentTemplates of(List<PaymentTemplateSpec> specs) {
        if (specs.size() > PaymentLimits.MAX_TEMPLATES) {
            throw new TemplateException(null, TemplateProblem.TOO_MANY_TEMPLATES);
        }
        List<PaymentTemplate> compiled = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (PaymentTemplateSpec spec : specs) {
            PaymentTemplate t = PaymentTemplate.compile(spec);
            if (!ids.add(t.id())) {
                throw new TemplateException(t.id(), TemplateProblem.DUPLICATE_TEMPLATE_ID);
            }
            compiled.add(t);
        }
        PaymentTemplates all = new PaymentTemplates(compiled);
        for (int i = 0; i < specs.size(); i++) {
            selfTest(specs.get(i), compiled.get(i), all);
        }
        return all;
    }

    private static void selfTest(PaymentTemplateSpec spec, PaymentTemplate template, PaymentTemplates all) {
        String id = template.id();
        if (spec.examples().size() < PaymentLimits.MIN_EXAMPLES) {
            throw new TemplateException(id, TemplateProblem.TOO_FEW_EXAMPLES);
        }
        if (spec.counterExamples().size() < PaymentLimits.MIN_COUNTER_EXAMPLES) {
            throw new TemplateException(id, TemplateProblem.TOO_FEW_COUNTER_EXAMPLES);
        }
        OrderParseContext context = new OrderParseContext(spec.currency(), spec.scale());
        PaymentTemplates onlyThis = new PaymentTemplates(List.of(template));
        for (PaymentTemplateSpec.Example example : spec.examples()) {
            String text = example.text();
            if (!text.equals(TextNormalizer.normalize(text))) {
                throw new TemplateException(id, TemplateProblem.EXAMPLE_NOT_NORMALISED);
            }
            ExtractionResult<ParsedPayment> alone = new PaymentTextParser(onlyThis, context).parse(text);
            if (!alone.isSuccess()) {
                throw new TemplateException(id, TemplateProblem.EXAMPLE_DID_NOT_PARSE);
            }
            ParsedPayment parsed = alone.value().orElseThrow();
            if (PaymentValidator.validate(parsed, context, SELF_TEST_NOW).verdict() != ValidationResult.Verdict.OK) {
                throw new TemplateException(id, TemplateProblem.EXAMPLE_DID_NOT_PARSE);
            }
            if (!matches(example.expect(), parsed)) {
                throw new TemplateException(id, TemplateProblem.EXAMPLE_WRONG_FIELDS);
            }
        }
        for (String counter : spec.counterExamples()) {
            if (template.match(TextNormalizer.normalize(counter)).isPresent()) {
                throw new TemplateException(id, TemplateProblem.COUNTER_EXAMPLE_MATCHED);
            }
        }
        // No other template may match this template's examples at all, whether or not it would read them the same way (this is
        // stricter than the runtime ambiguity rule, and it makes overlap a development error).
        for (PaymentTemplateSpec.Example example : spec.examples()) {
            for (PaymentTemplate other : all.templates) {
                if (!other.id().equals(id) && other.match(example.text()).isPresent()) {
                    throw new TemplateException(id, TemplateProblem.AMBIGUOUS_EXAMPLE);
                }
            }
        }
    }

    private static boolean matches(PaymentTemplateSpec.Expect e, ParsedPayment p) {
        return e != null
                && e.amountMinor() != null && e.amountMinor() == p.amountMinor()
                && java.util.Objects.equals(e.transactionId(), p.transactionId())
                && java.util.Objects.equals(e.senderName(), p.senderName())
                && java.util.Objects.equals(e.senderLast4(), p.senderLast4())
                && java.util.Objects.equals(e.reference(), p.reference())
                && e.paidAtUtc() != null && Instant.parse(e.paidAtUtc()).equals(p.paidAt());
    }
}
