package dev.chatledger.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke test for the ingestion module: verifies that request validation works.
 *
 * <p>Requirement: 9 (Security and privacy — validate every field).
 */
class IngestionRequestSmokeTest {

    @Test
    void valid_request_is_accepted() {
        var req = new IngestionRequest("ext-001", "Order: 2x Widget @ 1500 XAF");
        assertThat(req.externalId()).isEqualTo("ext-001");
    }

    @Test
    void blank_external_id_is_rejected() {
        assertThatThrownBy(() -> new IngestionRequest("  ", "some text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("externalId");
    }

    @Test
    void blank_text_is_rejected() {
        assertThatThrownBy(() -> new IngestionRequest("ext-002", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }
}
