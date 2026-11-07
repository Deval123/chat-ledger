package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** One key function for order references on both the order and the payment side. Task 5. */
class ReferenceKeysTest {

    @Test
    void the_key_ignores_case_and_surrounding_space_and_nothing_else() {
        assertThat(ReferenceKeys.key("a-1042")).isEqualTo("A-1042");
        assertThat(ReferenceKeys.key("  A-1042 ")).isEqualTo("A-1042");
        assertThat(ReferenceKeys.key("A-1042")).isEqualTo("A-1042");
        assertThat(ReferenceKeys.key("A-1043")).isNotEqualTo("A-1042");
        assertThat(ReferenceKeys.key("A_1042")).isNotEqualTo("A-1042");
        assertThat(ReferenceKeys.key("A-1042.")).isNotEqualTo("A-1042");
        assertThat(ReferenceKeys.key(null)).isEmpty();
    }

    @Test
    void two_references_are_the_same_only_when_both_exist_and_have_the_same_key() {
        assertThat(ReferenceKeys.same("a-1042", "A-1042")).isTrue();
        assertThat(ReferenceKeys.same("A-1042", "A-1043")).isFalse();
        assertThat(ReferenceKeys.same(null, null)).as("two missing references are not a match").isFalse();
        assertThat(ReferenceKeys.same("A-1042", null)).isFalse();
        assertThat(ReferenceKeys.same("", "")).isFalse();
    }

    @Test
    void an_order_reference_read_by_the_order_parser_equals_a_payment_reference_read_by_a_template() {
        ParsedOrder order = new OrderTextParser(new OrderParseContext("XAF", 0)).parse("Order: a-1042\n1 x rice @ 500").value().orElseThrow();
        ParsedPayment payment = new PaymentTextParser(TestSpecs.goodSet(), new OrderParseContext("XAF", 0))
                .parse("Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. Ref A-1042. 03/11/2026 10:15").value().orElseThrow();
        assertThat(order.reference()).isEqualTo("a-1042");
        assertThat(payment.reference()).isEqualTo("A-1042");
        assertThat(ReferenceKeys.same(order.reference(), payment.reference())).isTrue();
    }
}
