package dev.chatledger.payments;

import dev.chatledger.extraction.PaymentTemplateSpec;
import dev.chatledger.extraction.PaymentTemplates;
import dev.chatledger.extraction.TemplateException;
import dev.chatledger.extraction.TemplateProblem;
import dev.chatledger.extraction.TestSpecs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The application does not start with a template that has not been proven: a broken template that was skipped would turn a whole
 * provider's payments into unparsed ones unnoticed. The failure names the template and a code, nothing else.
 *
 * <p>Requirements: 2.2. Task 5.1. Design decision D3.
 */
class PaymentTemplateConfigTest {

    private final PaymentTemplateConfig config = new PaymentTemplateConfig();

    @Test
    void a_valid_source_gives_the_proven_set() {
        PaymentTemplates set = config.paymentTemplates(() -> List.of(TestSpecs.good(), TestSpecs.goodRef()));
        assertThat(set.all()).hasSize(2);
    }

    @Test
    void a_template_that_fails_its_proof_stops_startup_with_a_code_only() {
        PaymentTemplateSpec g = TestSpecs.good();
        List<String> counters = new ArrayList<>(g.counterExamples());
        counters.set(0, "Received 5000 XAF from CANARY-name (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15");
        PaymentTemplateSpec broken = new PaymentTemplateSpec(g.id(), g.provider(), g.currency(), g.scale(), true, g.zone(), g.dateFormat(),
                g.template(), g.examples(), counters);
        assertThatThrownBy(() -> config.paymentTemplates(() -> List.of(broken))).isInstanceOf(IllegalStateException.class)
                .hasMessage("Payment templates are not usable: template good: COUNTER_EXAMPLE_MATCHED")
                .satisfies(e -> assertThat(e.getCause()).isNull());
    }

    @Test
    void a_source_that_cannot_be_read_stops_startup_with_a_code_only() {
        assertThatThrownBy(() -> config.paymentTemplates(() -> {
            throw new TemplateException(null, TemplateProblem.BAD_JSON);
        })).isInstanceOf(IllegalStateException.class).hasMessage("Payment templates are not usable: template (invalid id): BAD_JSON");
    }

    @Test
    void one_bad_template_among_good_ones_still_stops_startup() {
        PaymentTemplateSpec g = TestSpecs.good();
        PaymentTemplateSpec tooFew = new PaymentTemplateSpec("short", g.provider(), g.currency(), g.scale(), true, g.zone(), g.dateFormat(),
                g.template().replace("Received", "Obtained"), g.examples().subList(0, 1), g.counterExamples());
        assertThatThrownBy(() -> config.paymentTemplates(() -> List.of(TestSpecs.good(), tooFew))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOO_FEW_EXAMPLES");
    }

    @Test
    void the_real_source_reads_the_jar() {
        PaymentTemplates set = config.paymentTemplates(new ClasspathPaymentTemplateSource());
        assertThat(set.all()).hasSize(5);
    }

    @Test
    void a_missing_or_empty_location_gives_an_empty_set_which_parses_nothing_rather_than_something_wrong() {
        PaymentTemplates none = config.paymentTemplates(new ClasspathPaymentTemplateSource("classpath*:no-such-folder/*.json"));
        assertThat(none.all()).isEmpty();
    }
}
