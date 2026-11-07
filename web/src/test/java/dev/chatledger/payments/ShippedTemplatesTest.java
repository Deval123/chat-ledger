package dev.chatledger.payments;

import dev.chatledger.extraction.HostileCorpus;
import dev.chatledger.extraction.HostilePayments;
import dev.chatledger.extraction.OrderParseContext;
import dev.chatledger.extraction.ParsedPayment;
import dev.chatledger.extraction.PaymentTemplate;
import dev.chatledger.extraction.PaymentTemplateSpec;
import dev.chatledger.extraction.PaymentTemplates;
import dev.chatledger.extraction.PaymentTextParser;
import dev.chatledger.extraction.ReferenceKeys;
import dev.chatledger.ingestion.PaymentDecider;
import dev.chatledger.ingestion.PaymentPipeline;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The templates that ship in the jar: they load, they prove themselves, they are synthetic, they cover a provider with and
 * without a reference, and nothing in the hostile corpora is read as a payment by accident. These are unit tests: they need no
 * database and no container.
 *
 * <p>Requirements: 2.2, 8. Tasks 5.1, 5.2.
 */
class ShippedTemplatesTest {

    private static final List<PaymentTemplateSpec> SPECS = new ClasspathPaymentTemplateSource().load();
    private static final PaymentTemplates SET = PaymentTemplates.of(SPECS);
    private static final OrderParseContext XAF = new OrderParseContext("XAF", 0);

    @Test
    void the_shipped_templates_load_and_pass_their_own_proof() {
        assertThat(SET.all()).extracting(PaymentTemplate::id).containsExactlyInAnyOrder("provider-a-forwarded", "provider-a-received",
                "provider-a-received-ref", "provider-b-recu", "provider-b-recu-ref");
        assertThat(SPECS).allSatisfy(s -> {
            assertThat(s.examples()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(s.counterExamples()).hasSizeGreaterThanOrEqualTo(5);
        });
    }

    @Test
    void two_synthetic_providers_each_with_and_without_a_reference_and_one_forwarded_shape() {
        Set<String> providers = new HashSet<>();
        SPECS.forEach(s -> providers.add(s.provider()));
        assertThat(providers).containsExactlyInAnyOrder("PROVIDER_A", "PROVIDER_B");
        for (String provider : providers) {
            var withRef = SPECS.stream().filter(s -> s.provider().equals(provider) && s.template().contains("{reference}")).count();
            var withoutRef = SPECS.stream().filter(s -> s.provider().equals(provider) && !s.template().contains("{reference}")
                    && s.transactionIdRequired()).count();
            assertThat(withRef).as(provider + " with a reference").isGreaterThanOrEqualTo(1);
            assertThat(withoutRef).as(provider + " without a reference").isGreaterThanOrEqualTo(1);
        }
        assertThat(SPECS.stream().filter(s -> !s.transactionIdRequired())).as("the forwarded shape has no transaction id").hasSize(1);
        assertThat(SPECS).allSatisfy(s -> assertThat(s.currency()).isEqualTo("XAF"));
    }

    @Test
    void both_languages_and_all_spellings_of_the_franc_are_covered_by_examples() {
        String all = String.join("\n", SPECS.stream().flatMap(s -> s.examples().stream()).map(PaymentTemplateSpec.Example::text).toList());
        assertThat(all).contains("You received", "Vous avez re\u00E7u", "XAF", "FCFA", "Motif :", " Ref ", "Forwarded:");
        assertThat(all).as("accented synthetic names are among the examples").contains("A\u00EFcha");
    }

    @Test
    void shipped_content_is_synthetic_no_real_looking_numbers_or_operator_names() {
        Pattern phoneLike = Pattern.compile("\\+?\\d[\\d ]{7,}\\d");
        Pattern runOfDigits = Pattern.compile("\\d{7,}");
        for (PaymentTemplateSpec s : SPECS) {
            List<String> texts = new java.util.ArrayList<>(s.counterExamples());
            s.examples().forEach(e -> texts.add(e.text()));
            texts.add(s.template());
            for (String text : texts) {
                Matcher m = phoneLike.matcher(text);
                while (m.find()) {
                    String digits = m.group().replaceAll("[^0-9]", "");
                    // Examples that carry a full number carry only numbers in the reserved synthetic range 999-000-000-xxxx.
                    assertThat(digits).as("a number in " + s.id()).startsWith("999000000");
                }
                assertThat(runOfDigits.matcher(text.replaceAll("\\D*999 000 000 \\d{4}", "")).find()).as(s.id()).isFalse();
            }
            assertThat(s.provider()).isIn("PROVIDER_A", "PROVIDER_B");
            assertThat(s.id()).startsWith("provider-");
        }
    }

    @Test
    void every_example_goes_through_the_whole_pipeline_to_the_status_its_template_promises() {
        for (PaymentTemplateSpec s : SPECS) {
            for (PaymentTemplateSpec.Example e : s.examples()) {
                PaymentDecider.Draft d = PaymentPipeline.run(e.text(), SET, XAF, PaymentTemplates.SELF_TEST_NOW);
                assertThat(d.status()).as(e.text()).isEqualTo(s.transactionIdRequired() ? PaymentDecider.Status.RECEIVED
                        : PaymentDecider.Status.UNVERIFIED);
                assertThat(d.parsed().provider()).isEqualTo(s.provider());
                assertThat(d.parsed().templateId()).isEqualTo(s.id());
            }
        }
    }

    @Test
    void a_reference_in_the_text_is_the_one_an_order_can_be_found_by() {
        ParsedPayment p = new PaymentTextParser(SET, XAF)
                .parse("You received 5000 XAF from Ana K. (+999 000 000 1234). Txn PA7K2M9X. Ref a-1042. 03/11/2026 10:15").value().orElseThrow();
        assertThat(p.reference()).isEqualTo("a-1042");
        assertThat(ReferenceKeys.same(p.reference(), "A-1042")).isTrue();
        ParsedPayment fr = new PaymentTextParser(SET, XAF)
                .parse("Vous avez re\u00E7u 5 000 FCFA de A\u00EFcha N. (+999 000 000 1234). ID transaction : PB93H27L. Motif : A-1042. Le 03/11/2026 10:15")
                .value().orElseThrow();
        assertThat(ReferenceKeys.same(fr.reference(), "a-1042")).isTrue();
    }

    @Test
    void no_order_message_and_no_hostile_text_is_read_as_a_shipped_payment() {
        PaymentTextParser parser = new PaymentTextParser(SET, XAF);
        HostileCorpus.all().forEach((name, text) -> assertThat(parser.parse(text).isSuccess()).as("order corpus: " + name).isFalse());
        HostilePayments.all().forEach((name, text) -> assertThat(parser.parse(text).isSuccess()).as("payment corpus: " + name).isFalse());
    }

    @Test
    void whatever_the_text_the_work_per_template_stays_under_its_fixed_ceiling() {
        List<String> texts = new java.util.ArrayList<>(HostilePayments.all().values());
        texts.addAll(HostileCorpus.all().values());
        texts.add("A".repeat(3_000_000));
        texts.add("9 ".repeat(1_500_000));
        for (PaymentTemplate t : SET.all()) {
            long worst = 0;
            for (String text : texts) {
                worst = Math.max(worst, t.scan(text).steps());
            }
            assertThat(worst).as(t.id()).isLessThan(1_000);
        }
    }
}
