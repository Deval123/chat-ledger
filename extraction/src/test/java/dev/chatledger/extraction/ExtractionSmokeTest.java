package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core types behave as value objects, and a failure can only carry a code, never text.
 *
 * <p>Requirement: 1 (Capture an order), 8 (Extraction pipeline).
 */
class ExtractionSmokeTest {

    private static ParsedOrder order(String name, Phone phone, List<ParsedOrder.OrderLine> lines, Long total) {
        return new ParsedOrder("A-1", name, phone, lines, total, "XAF", null, "raw");
    }

    @Test
    void success_result_wraps_parsed_value() {
        var order = order("Alice", null, List.of(), 5000L);
        ExtractionResult<ParsedOrder> result = new ExtractionResult.Success<>(order);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).contains(order);
        assertThat(result.failureReason()).isEmpty();
    }

    @Test
    void failure_result_carries_a_code() {
        ExtractionResult<ParsedOrder> result = new ExtractionResult.Failure<>(FailureReason.NO_ITEMS);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.value()).isEmpty();
        assertThat(result.failureReason()).contains(FailureReason.NO_ITEMS);
    }

    @Test
    void failure_reason_is_an_enum_so_no_message_text_can_travel_in_it() throws Exception {
        var component = ExtractionResult.Failure.class.getRecordComponents()[0];
        assertThat(component.getType()).isEqualTo(FailureReason.class);
        assertThat(FailureReason.class.isEnum()).isTrue();
    }

    @Test
    void parsed_order_lines_are_an_immutable_copy() {
        var line = new ParsedOrder.OrderLine("Widget", 2, 1500L, null);
        var lines = new ArrayList<>(List.of(line));
        var order = order(null, null, lines, null);
        lines.clear();
        assertThat(order.lines()).containsExactly(line);
    }

    @Test
    void printing_an_order_or_a_payment_reveals_no_personal_data() {
        Phone phone = Phone.of("+999 000 000 1234").orElseThrow();
        var order = new ParsedOrder("A-1", "Secret Name", phone,
                List.of(new ParsedOrder.OrderLine("Secret Item", 1, 100L, null)), 100L, "XAF", "Secret Note", "Secret Raw");
        for (String printed : new String[]{order.toString(), order.lines().get(0).toString(), phone.toString(),
                new ParsedPayment("P", "T", 1, "XAF", "Secret Sender", "1234", null, "Secret Raw").toString()}) {
            assertThat(printed).doesNotContain("Secret").doesNotContain("9990000001234").doesNotContain("999 000");
        }
        assertThat(phone.toString()).isEqualTo("Phone[****1234]");
    }
}
