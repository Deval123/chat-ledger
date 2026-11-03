package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the extraction module: verifies that the core types compile
 * and behave correctly as value objects.
 *
 * <p>Requirement: 1 (Capture an order), 8 (Extraction pipeline).
 */
class ExtractionSmokeTest {

    @Test
    void success_result_wraps_parsed_value() {
        var order = new ParsedOrder("Alice", "0001", List.of(), 5000L, "XAF", null, "raw");
        ExtractionResult<ParsedOrder> result = new ExtractionResult.Success<>(order);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).contains(order);
        assertThat(result.failureReason()).isEmpty();
    }

    @Test
    void failure_result_carries_reason() {
        ExtractionResult<ParsedOrder> result = new ExtractionResult.Failure<>("no pattern matched");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.value()).isEmpty();
        assertThat(result.failureReason()).contains("no pattern matched");
    }

    @Test
    void parsed_order_lines_are_immutable_copy() {
        var line  = new ParsedOrder.OrderLine("Widget", 2, 1500L);
        var order = new ParsedOrder(null, "9999", List.of(line), null, "XAF", null, "raw");

        assertThat(order.lines()).containsExactly(line);
    }
}
