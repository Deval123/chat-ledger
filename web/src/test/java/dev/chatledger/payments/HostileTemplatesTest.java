package dev.chatledger.payments;

import dev.chatledger.extraction.PaymentTemplateSpec;
import dev.chatledger.extraction.PaymentTemplates;
import dev.chatledger.extraction.TemplateException;
import dev.chatledger.extraction.TemplateProblem;
import dev.chatledger.extraction.TestSpecs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A corpus of hostile, careless and clever templates. Every one must be refused with a code before it can read a message. The
 * point of the design is that a template is literal text with typed holes: there is no pattern language to attack, so the
 * dangerous inputs below are either refused or are just literal text.
 *
 * <p>Requirements: 2.2, 8, 9. Task 5.1.
 */
class HostileTemplatesTest {

    private static PaymentTemplateSpec spec(String template) {
        PaymentTemplateSpec g = TestSpecs.good();
        return new PaymentTemplateSpec("hostile", g.provider(), g.currency(), g.scale(), template.contains("{transactionId}"), g.zone(),
                g.dateFormat(), template, g.examples(), g.counterExamples());
    }

    private static final Map<String, TemplateProblem> REFUSED = new TreeMap<>();

    static {
        // Pattern syntax written as if this were a pattern language: braces make holes, so repetition counts are unknown holes.
        REFUSED.put("regex with named groups", TemplateProblem.UNKNOWN_HOLE);
        REFUSED.put("bounded repetition", TemplateProblem.UNKNOWN_HOLE);
        REFUSED.put("a hole with a modifier", TemplateProblem.UNKNOWN_HOLE);
        REFUSED.put("a hole with a pattern inside", TemplateProblem.UNKNOWN_HOLE);
        REFUSED.put("a hole with spaces", TemplateProblem.UNKNOWN_HOLE);
        REFUSED.put("a hole name in another case", TemplateProblem.UNKNOWN_HOLE);
        REFUSED.put("an escaped brace", TemplateProblem.UNKNOWN_HOLE);
        REFUSED.put("a closing brace alone", TemplateProblem.STRAY_BRACE);
        REFUSED.put("an unclosed hole at the end", TemplateProblem.UNTERMINATED_HOLE);
        REFUSED.put("a hole that never closes", TemplateProblem.UNTERMINATED_HOLE);
        REFUSED.put("the same hole twice", TemplateProblem.DUPLICATE_HOLE);
        REFUSED.put("two holes next to each other", TemplateProblem.ADJACENT_HOLES);
        REFUSED.put("a template that matches everything", TemplateProblem.TOO_FEW_LITERALS);
        REFUSED.put("only holes and punctuation", TemplateProblem.TOO_FEW_LITERALS);
        REFUSED.put("a very long template", TemplateProblem.TEMPLATE_TOO_LONG);
        REFUSED.put("a name hole that swallows the words after it", TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);
        REFUSED.put("an id hole followed by an id character", TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);
        REFUSED.put("an amount followed by a digit separator", TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);
        REFUSED.put("two holes that share characters", TemplateProblem.DELIMITER_IN_HOLE_CHARACTERS);
        REFUSED.put("a template with a hole for the provider", TemplateProblem.UNKNOWN_HOLE);
        REFUSED.put("no amount", TemplateProblem.MISSING_REQUIRED_HOLE);
        REFUSED.put("no time", TemplateProblem.MISSING_REQUIRED_HOLE);
        REFUSED.put("double spaces", TemplateProblem.NOT_NORMALISED);
        REFUSED.put("a tab", TemplateProblem.NOT_NORMALISED);
        REFUSED.put("an invisible character", TemplateProblem.NOT_NORMALISED);
        REFUSED.put("a right-to-left override", TemplateProblem.NOT_NORMALISED);
        REFUSED.put("a trailing space", TemplateProblem.NOT_NORMALISED);
        REFUSED.put("both a full phone and a last four", TemplateProblem.SENDER_CONFLICT);
    }

    private static final Map<String, String> TEMPLATES = new TreeMap<>(Map.ofEntries(
            Map.entry("regex with named groups", "^You received (?P<amount>[0-9 ,.]{1,15}) from (?P<paidAt>.*)$"),
            Map.entry("bounded repetition", "Paid ok{1,15} {amount} at {paidAt}"),
            Map.entry("a hole with a modifier", "Paid {amount:int} XAF at {paidAt}"),
            Map.entry("a hole with a pattern inside", "Paid {amount[0-9]+} XAF at {paidAt}"),
            Map.entry("a hole with spaces", "Paid { amount } XAF at {paidAt}"),
            Map.entry("a hole name in another case", "Paid {AMOUNT} XAF at {paidAt}"),
            Map.entry("an escaped brace", "Paid \\{amount\\} XAF at {paidAt}"),
            Map.entry("a closing brace alone", "Paid {amount} XAF at} {paidAt}"),
            Map.entry("an unclosed hole at the end", "Paid {amount} XAF at {paidAt"),
            Map.entry("a hole that never closes", "Paid {amount XAF at some time and then {paidAt} ok"),
            Map.entry("the same hole twice", "Paid {amount} XAF and {amount} XAF at {paidAt}"),
            Map.entry("two holes next to each other", "Paid {amount}{paidAt} XAF at some time"),
            Map.entry("a template that matches everything", "{amount} {paidAt}"),
            Map.entry("only holes and punctuation", "{amount}, {paidAt}!"),
            Map.entry("a very long template", "Paid " + "x".repeat(600) + " {amount} at {paidAt}"),
            Map.entry("a name hole that swallows the words after it", "Paid {amount} XAF by {senderName} on the day {paidAt}"),
            Map.entry("an id hole followed by an id character", "Paid {amount} XAF id {transactionId}x at {paidAt}"),
            Map.entry("an amount followed by a digit separator", "Paid {amount}.50 XAF at {paidAt}"),
            Map.entry("two holes that share characters", "Paid {senderName} {reference} XAF at {paidAt} {amount}"),
            Map.entry("a template with a hole for the provider", "Paid {amount} XAF to {provider} at {paidAt}"),
            Map.entry("no amount", "Paid some money to Ana at {paidAt}, thank you"),
            Map.entry("no time", "Paid {amount} XAF to Ana on a day, thank you"),
            Map.entry("double spaces", "Paid  {amount} XAF at {paidAt}"),
            Map.entry("a tab", "Paid\t{amount} XAF at {paidAt}"),
            Map.entry("an invisible character", "Paid {amount} XAF\u200B at {paidAt}"),
            Map.entry("a right-to-left override", "Paid {amount} XAF \u202E at {paidAt}"),
            Map.entry("a trailing space", "Paid {amount} XAF at {paidAt} "),
            Map.entry("both a full phone and a last four", "Paid {amount} XAF by {senderName} ({senderPhone}) ({senderLast4}) at {paidAt}")));

    @Test
    void the_two_lists_describe_the_same_cases() {
        assertThat(TEMPLATES.keySet()).isEqualTo(REFUSED.keySet());
    }

    @Test
    void every_hostile_template_is_refused_with_its_code_before_it_can_read_anything() {
        TEMPLATES.forEach((name, template) -> assertThatThrownBy(() -> PaymentTemplates.of(List.of(spec(template))))
                .as(name).isInstanceOfSatisfying(TemplateException.class, e -> assertThat(e.problem()).as(name).isEqualTo(REFUSED.get(name))));
    }

    @Test
    void a_refusal_never_repeats_the_template() {
        TEMPLATES.forEach((name, template) -> assertThatThrownBy(() -> PaymentTemplates.of(List.of(spec(template)))).as(name)
                .satisfies(e -> {
                    assertThat(e.getMessage()).startsWith("template hostile: ");
                    assertThat(e.getMessage().length()).isLessThan(70);
                    assertThat(e.getCause()).isNull();
                }));
    }

    @Test
    void pattern_characters_in_a_template_are_just_literal_text() {
        // The template below would be a catastrophic pattern. As a template it is plain text that matches only itself.
        PaymentTemplateSpec g = TestSpecs.good();
        String template = "Paid (a+)+$ .* [x] {amount} XAF at {paidAt}";
        PaymentTemplateSpec literal = new PaymentTemplateSpec("literal", g.provider(), g.currency(), g.scale(), false, g.zone(),
                g.dateFormat(), template, List.of(), List.of());
        var t = dev.chatledger.extraction.PaymentTemplate.compile(literal);
        assertThat(t.match("Paid (a+)+$ .* [x] 5000 XAF at 03/11/2026 10:15")).isPresent();
        assertThat(t.match("Paid aaaa 5000 XAF at 03/11/2026 10:15")).isEmpty();
        assertThat(t.match("Paid anything 5000 XAF at 03/11/2026 10:15")).isEmpty();
        assertThat(t.scan("Paid " + "a".repeat(1_000_000) + "!").steps()).isLessThan(100);
    }

    @Test
    void markup_and_scripts_in_a_template_are_literal_text_the_template_author_is_not_trusted_with_output_but_cannot_use_it_as_code() {
        PaymentTemplateSpec g = TestSpecs.good();
        PaymentTemplateSpec scripted = new PaymentTemplateSpec("scripted", g.provider(), g.currency(), g.scale(), false, g.zone(), g.dateFormat(),
                "Paid {amount} XAF <script>alert(1)</script> at {paidAt}", List.of(), List.of());
        var t = dev.chatledger.extraction.PaymentTemplate.compile(scripted);
        assertThat(t.match("Paid 5000 XAF <script>alert(1)</script> at 03/11/2026 10:15").orElseThrow())
                .as("the holes still only take their own characters; the literal is never output").containsOnlyKeys(
                        dev.chatledger.extraction.HoleType.AMOUNT, dev.chatledger.extraction.HoleType.PAID_AT);
        assertThat(t.match("Paid 5000 XAF at 03/11/2026 10:15")).isEmpty();
    }
}
