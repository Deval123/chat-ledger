package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The proof a set of templates must pass before it can be used: its own examples, its own counter-examples, and no overlap with
 * its neighbours. A set that fails is never built (the application would not start).
 *
 * <p>Requirements: 2.2, 8. Task 5.1, 5.2.
 */
class PaymentTemplatesTest {

    private static PaymentTemplateSpec with(PaymentTemplateSpec base, List<PaymentTemplateSpec.Example> examples, List<String> counters) {
        return new PaymentTemplateSpec(base.id(), base.provider(), base.currency(), base.scale(), base.transactionIdRequired(), base.zone(),
                base.dateFormat(), base.template(), examples, counters);
    }

    private static void refuses(List<PaymentTemplateSpec> specs, TemplateProblem problem) {
        assertThatThrownBy(() -> PaymentTemplates.of(specs)).as(problem.name()).isInstanceOfSatisfying(TemplateException.class,
                e -> assertThat(e.problem()).isEqualTo(problem));
    }

    @Test
    void a_valid_set_is_built_and_keeps_its_templates() {
        PaymentTemplates set = PaymentTemplates.of(List.of(TestSpecs.good(), TestSpecs.goodRef()));
        assertThat(set.all()).extracting(PaymentTemplate::id).containsExactly("good", "good-ref");
    }

    @Test
    void the_examples_are_checked_against_a_fixed_clock_so_they_do_not_expire() {
        assertThat(PaymentTemplates.SELF_TEST_NOW).isEqualTo(java.time.Instant.parse("2026-11-03T12:00:00Z"));
        assertThatCode(() -> PaymentTemplates.of(List.of(TestSpecs.good()))).doesNotThrowAnyException();
    }

    @Test
    void too_many_templates_and_duplicate_ids() {
        List<PaymentTemplateSpec> many = new ArrayList<>();
        for (int i = 0; i <= PaymentLimits.MAX_TEMPLATES; i++) {
            many.add(TestSpecs.good());
        }
        refuses(many, TemplateProblem.TOO_MANY_TEMPLATES);
        refuses(List.of(TestSpecs.good(), TestSpecs.good()), TemplateProblem.DUPLICATE_TEMPLATE_ID);
    }

    @Test
    void a_template_needs_enough_examples_and_counter_examples() {
        PaymentTemplateSpec g = TestSpecs.good();
        refuses(List.of(with(g, g.examples().subList(0, 2), g.counterExamples())), TemplateProblem.TOO_FEW_EXAMPLES);
        refuses(List.of(with(g, g.examples(), g.counterExamples().subList(0, 4))), TemplateProblem.TOO_FEW_COUNTER_EXAMPLES);
        refuses(List.of(with(g, List.of(), List.of())), TemplateProblem.TOO_FEW_EXAMPLES);
    }

    @Test
    void an_example_must_be_written_in_normalised_form() {
        PaymentTemplateSpec g = TestSpecs.good();
        List<PaymentTemplateSpec.Example> ex = new ArrayList<>(g.examples());
        PaymentTemplateSpec.Example first = ex.get(0);
        ex.set(0, new PaymentTemplateSpec.Example(first.text().replace("Received 5000", "Received  5000"), first.expect()));
        refuses(List.of(with(g, ex, g.counterExamples())), TemplateProblem.EXAMPLE_NOT_NORMALISED);
    }

    @Test
    void an_example_must_match_its_own_template() {
        PaymentTemplateSpec g = TestSpecs.good();
        List<PaymentTemplateSpec.Example> ex = new ArrayList<>(g.examples());
        PaymentTemplateSpec.Example first = ex.get(0);
        ex.set(0, new PaymentTemplateSpec.Example("Sent 5000 XAF to Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15", first.expect()));
        refuses(List.of(with(g, ex, g.counterExamples())), TemplateProblem.EXAMPLE_DID_NOT_PARSE);
    }

    @Test
    void an_example_must_give_exactly_the_fields_it_claims() {
        PaymentTemplateSpec g = TestSpecs.good();
        PaymentTemplateSpec.Example first = g.examples().get(0);
        PaymentTemplateSpec.Expect e = first.expect();
        List<PaymentTemplateSpec.Expect> wrong = List.of(
                new PaymentTemplateSpec.Expect(5001L, e.transactionId(), e.senderName(), e.senderLast4(), e.reference(), e.paidAtUtc()),
                new PaymentTemplateSpec.Expect(e.amountMinor(), "TX000000", e.senderName(), e.senderLast4(), e.reference(), e.paidAtUtc()),
                new PaymentTemplateSpec.Expect(e.amountMinor(), e.transactionId(), "Someone", e.senderLast4(), e.reference(), e.paidAtUtc()),
                new PaymentTemplateSpec.Expect(e.amountMinor(), e.transactionId(), e.senderName(), "0000", e.reference(), e.paidAtUtc()),
                new PaymentTemplateSpec.Expect(e.amountMinor(), e.transactionId(), e.senderName(), e.senderLast4(), "A-1", e.paidAtUtc()),
                new PaymentTemplateSpec.Expect(e.amountMinor(), e.transactionId(), e.senderName(), e.senderLast4(), e.reference(), "2026-11-03T10:15:00Z"),
                new PaymentTemplateSpec.Expect(null, e.transactionId(), e.senderName(), e.senderLast4(), e.reference(), e.paidAtUtc()),
                new PaymentTemplateSpec.Expect(e.amountMinor(), e.transactionId(), e.senderName(), e.senderLast4(), e.reference(), null));
        for (PaymentTemplateSpec.Expect w : wrong) {
            List<PaymentTemplateSpec.Example> ex = new ArrayList<>(g.examples());
            ex.set(0, new PaymentTemplateSpec.Example(first.text(), w));
            refuses(List.of(with(g, ex, g.counterExamples())), TemplateProblem.EXAMPLE_WRONG_FIELDS);
        }
        List<PaymentTemplateSpec.Example> noExpect = new ArrayList<>(g.examples());
        noExpect.set(0, new PaymentTemplateSpec.Example(first.text(), null));
        refuses(List.of(with(g, noExpect, g.counterExamples())), TemplateProblem.EXAMPLE_WRONG_FIELDS);
    }

    @Test
    void an_example_that_the_validator_would_refuse_is_not_an_example() {
        PaymentTemplateSpec g = TestSpecs.good();
        List<PaymentTemplateSpec.Example> ex = new ArrayList<>(g.examples());
        PaymentTemplateSpec.Example first = ex.get(0);
        // A date in 2020 is outside the plausibility window of the fixed clock.
        ex.set(0, TestSpecs.example(first.text().replace("2026", "2020"), 5000, "TX123456", "Ana K.", "1234", null, "2020-11-03T09:15:00Z"));
        refuses(List.of(with(g, ex, g.counterExamples())), TemplateProblem.EXAMPLE_DID_NOT_PARSE);
    }

    @Test
    void a_counter_example_that_matches_is_refused() {
        PaymentTemplateSpec g = TestSpecs.good();
        List<String> counters = new ArrayList<>(g.counterExamples());
        counters.set(2, HostilePayments.VALID);
        refuses(List.of(with(g, g.examples(), counters)), TemplateProblem.COUNTER_EXAMPLE_MATCHED);
    }

    @Test
    void a_counter_example_is_tested_in_normalised_form_so_extra_spaces_cannot_hide_a_match() {
        PaymentTemplateSpec g = TestSpecs.good();
        List<String> counters = new ArrayList<>(g.counterExamples());
        counters.set(2, "Received  5000 XAF from Ana K.   (+999 000 000 1234). Txn TX123456.\t03/11/2026 10:15");
        refuses(List.of(with(g, g.examples(), counters)), TemplateProblem.COUNTER_EXAMPLE_MATCHED);
    }

    @Test
    void two_templates_may_not_both_match_an_example() {
        PaymentTemplateSpec g = TestSpecs.good();
        PaymentTemplateSpec twin = new PaymentTemplateSpec("twin", "PROVIDER_X", g.currency(), g.scale(), true, g.zone(), g.dateFormat(),
                g.template(), g.examples(), g.counterExamples());
        refuses(List.of(g, twin), TemplateProblem.AMBIGUOUS_EXAMPLE);
    }

    @Test
    void two_templates_that_would_give_the_same_result_still_may_not_share_an_example() {
        // Same provider, same text: at runtime they would agree, but the proof is stricter on purpose. Overlap is a development error.
        PaymentTemplateSpec g = TestSpecs.good();
        PaymentTemplateSpec twin = new PaymentTemplateSpec("twin", g.provider(), g.currency(), g.scale(), true, g.zone(), g.dateFormat(),
                g.template(), g.examples(), g.counterExamples());
        refuses(List.of(g, twin), TemplateProblem.AMBIGUOUS_EXAMPLE);
    }

    @Test
    void a_template_with_a_reference_and_one_without_do_not_overlap() {
        assertThatCode(() -> PaymentTemplates.of(List.of(TestSpecs.good(), TestSpecs.goodRef()))).doesNotThrowAnyException();
        PaymentTemplates set = TestSpecs.goodSet();
        for (var spec : List.of(TestSpecs.good(), TestSpecs.goodRef())) {
            for (var ex : spec.examples()) {
                long matching = set.all().stream().filter(t -> t.match(ex.text()).isPresent()).count();
                assertThat(matching).as(ex.text()).isEqualTo(1);
            }
        }
    }

    @Test
    void a_failed_proof_names_the_template_and_the_problem_and_no_text() {
        PaymentTemplateSpec g = TestSpecs.good();
        List<String> counters = new ArrayList<>(g.counterExamples());
        counters.set(0, HostilePayments.VALID);
        assertThatThrownBy(() -> PaymentTemplates.of(List.of(with(g, g.examples(), counters)))).hasMessage("template good: COUNTER_EXAMPLE_MATCHED");
    }
}
