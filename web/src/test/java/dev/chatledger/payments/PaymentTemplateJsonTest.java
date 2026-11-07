package dev.chatledger.payments;

import dev.chatledger.extraction.PaymentTemplateSpec;
import dev.chatledger.extraction.TemplateException;
import dev.chatledger.extraction.TemplateProblem;
import dev.chatledger.extraction.TestSpecs;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A template file is bound strictly: nothing unknown, nothing repeated, nothing of the wrong type, nothing missing, nothing
 * big, and a refusal carries a code and never the file's text.
 *
 * <p>Requirements: 2.2, 8. Task 5.1.
 */
class PaymentTemplateJsonTest {

    private static final JsonMapper WRITER = JsonMapper.builder().build();

    /** The valid synthetic template of the tests as a mutable document. */
    private static Map<String, Object> base() {
        PaymentTemplateSpec g = TestSpecs.good();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id());
        m.put("provider", g.provider());
        m.put("currency", g.currency());
        m.put("scale", g.scale());
        m.put("transactionIdRequired", g.transactionIdRequired());
        m.put("zone", g.zone());
        m.put("dateFormat", g.dateFormat());
        m.put("template", g.template());
        List<Object> examples = new ArrayList<>();
        for (PaymentTemplateSpec.Example e : g.examples()) {
            Map<String, Object> expect = new LinkedHashMap<>();
            expect.put("amount", e.expect().amountMinor());
            expect.put("transactionId", e.expect().transactionId());
            expect.put("senderName", e.expect().senderName());
            expect.put("senderLast4", e.expect().senderLast4());
            expect.put("reference", e.expect().reference());
            expect.put("paidAt", e.expect().paidAtUtc());
            Map<String, Object> ex = new LinkedHashMap<>();
            ex.put("text", e.text());
            ex.put("expect", expect);
            examples.add(ex);
        }
        m.put("examples", examples);
        m.put("counterExamples", new ArrayList<>(g.counterExamples()));
        return m;
    }

    private static String json(Map<String, Object> m) {
        return WRITER.writeValueAsString(m);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstExample(Map<String, Object> m) {
        return (Map<String, Object>) ((List<Object>) m.get("examples")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstExpect(Map<String, Object> m) {
        return (Map<String, Object>) firstExample(m).get("expect");
    }

    private static void refuses(String json, TemplateProblem problem) {
        assertThatThrownBy(() -> PaymentTemplateJson.parse(json)).as(problem.name()).isInstanceOfSatisfying(TemplateException.class,
                e -> assertThat(e.problem()).isEqualTo(problem));
    }

    @Test
    void a_valid_file_is_bound_field_for_field() {
        PaymentTemplateSpec spec = PaymentTemplateJson.parse(json(base()));
        PaymentTemplateSpec expected = TestSpecs.good();
        assertThat(spec.id()).isEqualTo(expected.id());
        assertThat(spec.provider()).isEqualTo(expected.provider());
        assertThat(spec.currency()).isEqualTo("XAF");
        assertThat(spec.scale()).isZero();
        assertThat(spec.transactionIdRequired()).isTrue();
        assertThat(spec.zone()).isEqualTo("+01:00");
        assertThat(spec.dateFormat()).isEqualTo("dd/MM/yyyy HH:mm");
        assertThat(spec.template()).isEqualTo(expected.template());
        assertThat(spec.examples()).isEqualTo(expected.examples());
        assertThat(spec.counterExamples()).isEqualTo(expected.counterExamples());
    }

    @Test
    void a_file_over_eight_kibibytes_is_refused_and_one_at_the_limit_is_read() {
        String valid = json(base());
        String atLimit = valid + " ".repeat(8192 - valid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(atLimit.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(8192);
        assertThat(PaymentTemplateJson.parse(atLimit).id()).isEqualTo("good");
        refuses(atLimit + " ", TemplateProblem.FILE_TOO_LARGE);
        refuses(null, TemplateProblem.FILE_TOO_LARGE);
        Map<String, Object> big = base();
        big.put("counterExamples", List.of("x".repeat(9000)));
        refuses(json(big), TemplateProblem.FILE_TOO_LARGE);
    }

    @Test
    void anything_that_is_not_one_json_object_is_refused() {
        for (String bad : new String[]{"", "{", "}", "[]", "null", "\"x\"", "42", "{'id':'x'}", "{} {}", "{\"id\":\"x\"} trailing", "<xml/>",
                "{\"id\":\"x\",}", "{/* comment */}", "{\"a\":NaN}"}) {
            refuses(bad, TemplateProblem.BAD_JSON);
        }
    }

    @Test
    void a_repeated_key_is_refused_at_every_level() {
        String valid = json(base());
        refuses(valid.replace("\"id\":\"good\"", "\"id\":\"good\",\"id\":\"other\""), TemplateProblem.BAD_JSON);
        refuses(valid.replace("\"text\":", "\"text\":\"a\",\"text\":"), TemplateProblem.BAD_JSON);
        refuses(valid.replace("\"amount\":5000", "\"amount\":5000,\"amount\":1"), TemplateProblem.BAD_JSON);
    }

    @Test
    void a_field_this_reader_does_not_know_is_refused_at_every_level() {
        Map<String, Object> top = base();
        top.put("flags", "i");
        refuses(json(top), TemplateProblem.UNKNOWN_FIELD);
        Map<String, Object> example = base();
        firstExample(example).put("note", "x");
        refuses(json(example), TemplateProblem.UNKNOWN_FIELD);
        Map<String, Object> expect = base();
        firstExpect(expect).put("currency", "XAF");
        refuses(json(expect), TemplateProblem.UNKNOWN_FIELD);
        Map<String, Object> pattern = base();
        pattern.put("pattern", "^(a+)+$");
        refuses(json(pattern), TemplateProblem.UNKNOWN_FIELD);
    }

    @Test
    void a_field_of_the_wrong_type_is_refused_and_nothing_is_converted() {
        for (String field : new String[]{"id", "provider", "currency", "zone", "dateFormat", "template"}) {
            Map<String, Object> m = base();
            m.put(field, 42);
            refuses(json(m), TemplateProblem.WRONG_TYPE);
        }
        Map<String, Object> scale = base();
        scale.put("scale", "0");
        refuses(json(scale), TemplateProblem.WRONG_TYPE);
        Map<String, Object> fraction = base();
        fraction.put("scale", 1.5);
        refuses(json(fraction), TemplateProblem.WRONG_TYPE);
        Map<String, Object> huge = base();
        huge.put("scale", 3_000_000_000L);
        refuses(json(huge), TemplateProblem.WRONG_TYPE);
        Map<String, Object> flag = base();
        flag.put("transactionIdRequired", "true");
        refuses(json(flag), TemplateProblem.WRONG_TYPE);
        Map<String, Object> examples = base();
        examples.put("examples", "none");
        refuses(json(examples), TemplateProblem.WRONG_TYPE);
        Map<String, Object> element = base();
        element.put("examples", List.of("text"));
        refuses(json(element), TemplateProblem.WRONG_TYPE);
        Map<String, Object> counter = base();
        counter.put("counterExamples", List.of(1, 2));
        refuses(json(counter), TemplateProblem.WRONG_TYPE);
        Map<String, Object> amount = base();
        firstExpect(amount).put("amount", "5000");
        refuses(json(amount), TemplateProblem.WRONG_TYPE);
        Map<String, Object> decimal = base();
        firstExpect(decimal).put("amount", 50.5);
        refuses(json(decimal), TemplateProblem.WRONG_TYPE);
        Map<String, Object> name = base();
        firstExpect(name).put("senderName", 7);
        refuses(json(name), TemplateProblem.WRONG_TYPE);
    }

    @Test
    void a_missing_field_is_refused_and_nothing_is_defaulted() {
        for (String field : new String[]{"id", "provider", "currency", "scale", "transactionIdRequired", "zone", "dateFormat", "template",
                "examples", "counterExamples"}) {
            Map<String, Object> m = base();
            m.remove(field);
            refuses(json(m), TemplateProblem.MISSING_FIELD);
            Map<String, Object> nulled = base();
            nulled.put(field, null);
            refuses(json(nulled), TemplateProblem.MISSING_FIELD);
        }
        Map<String, Object> noText = base();
        firstExample(noText).remove("text");
        refuses(json(noText), TemplateProblem.MISSING_FIELD);
        Map<String, Object> noExpect = base();
        firstExample(noExpect).remove("expect");
        refuses(json(noExpect), TemplateProblem.MISSING_FIELD);
    }

    @Test
    void optional_expectations_may_be_absent_or_null() {
        Map<String, Object> m = base();
        firstExpect(m).remove("reference");
        firstExpect(m).put("transactionId", null);
        PaymentTemplateSpec spec = PaymentTemplateJson.parse(json(m));
        assertThat(spec.examples().get(0).expect().reference()).isNull();
        assertThat(spec.examples().get(0).expect().transactionId()).isNull();
    }

    @Test
    void a_refusal_carries_a_code_and_never_text_from_the_file() {
        Map<String, Object> m = base();
        m.put("CANARY-field-name", "CANARY-value");
        assertThatThrownBy(() -> PaymentTemplateJson.parse(json(m))).isInstanceOfSatisfying(TemplateException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("template good: UNKNOWN_FIELD");
            assertThat(e.getMessage()).doesNotContain("CANARY");
            assertThat(e.getCause()).isNull();
        });
        assertThatThrownBy(() -> PaymentTemplateJson.parse("{\"CANARY\": nope}")).isInstanceOfSatisfying(TemplateException.class, e -> {
            assertThat(e.getMessage()).doesNotContain("CANARY").doesNotContain("nope");
            assertThat(e.getCause()).isNull();
        });
        Map<String, Object> badId = base();
        badId.put("id", 7);
        badId.put("provider", 7);
        assertThatThrownBy(() -> PaymentTemplateJson.parse(json(badId))).isInstanceOf(TemplateException.class)
                .hasMessage("template (invalid id): WRONG_TYPE");
    }
}
