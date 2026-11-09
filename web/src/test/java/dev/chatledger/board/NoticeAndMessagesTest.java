package dev.chatledger.board;

import dev.chatledger.matching.FloorCode;
import dev.chatledger.matching.MatchingService;
import dev.chatledger.matching.ReasonCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every code the pages can print has a fixed English sentence, and a notice travels only as one of its own names. A new status, reason or
 * notice without a sentence fails here, not in front of a user.
 *
 * <p>Requirements: 10.1 (fixed text), 9.5. Task 8.
 */
class NoticeAndMessagesTest {

    private static Properties messages() throws IOException {
        Properties p = new Properties();
        try (InputStream in = NoticeAndMessagesTest.class.getResourceAsStream("/messages.properties")) {
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void a_notice_is_read_back_only_by_its_exact_name() {
        for (Notice notice : Notice.values()) {
            assertThat(Notice.parse(notice.name())).contains(notice);
        }
        for (String bad : new String[]{null, "", "order_recorded", " ORDER_RECORDED", "ORDER_RECORDED ", "ORDER_RECORDED\n", "<script>", "../x", "X".repeat(41),
                "ORDER_RECORDED,PAYMENT_RECORDED", "\u0000"}) {
            assertThat(Notice.parse(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    void every_refusal_of_the_matching_service_has_its_own_notice() {
        for (MatchingService.Reason reason : MatchingService.Reason.values()) {
            assertThat(Notice.refusal(reason).name()).isEqualTo("REFUSED_" + reason.name());
        }
    }

    @Test
    void every_code_the_pages_print_has_a_sentence() throws IOException {
        Properties p = messages();
        List<String> missing = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Notice n : Notice.values()) {
            keys.add(n.key());
        }
        for (ReasonCode c : ReasonCode.values()) {
            keys.add("reason." + c.name());
        }
        for (FloorCode c : FloorCode.values()) {
            keys.add("floor." + c.name());
        }
        for (BoardRepository.Column c : BoardRepository.Column.values()) {
            keys.add("column." + c.name());
            keys.add("column.empty." + c.name());
        }
        for (BooksCheck.Code c : BooksCheck.Code.values()) {
            keys.add("books." + c.name());
        }
        for (String s : new String[]{"PENDING_PAYMENT", "PARTIALLY_PAID", "PAID", "NEEDS_REVIEW", "UNPARSED", "CANCELLED"}) {
            keys.add("status.ORDER." + s);
        }
        for (String s : new String[]{"RECEIVED", "MATCHED", "UNMATCHED", "DUPLICATE", "UNVERIFIED", "UNPARSED"}) {
            keys.add("status.PAYMENT." + s);
        }
        for (String s : new String[]{"SUGGESTED", "AUTO", "CONFIRMED", "REJECTED", "REVERSED"}) {
            keys.add("match.state." + s);
        }
        for (String s : new String[]{"you", "other", "system"}) {
            keys.add("match.decider." + s);
        }
        for (String s : new String[]{"SELLER", "HELPER"}) {
            keys.add("role." + s);
        }
        for (String key : keys) {
            if (p.getProperty(key) == null || p.getProperty(key).isBlank()) {
                missing.add(key);
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void no_message_with_arguments_uses_an_apostrophe_and_no_message_holds_markup() throws IOException {
        Properties p = messages();
        for (String key : p.stringPropertyNames()) {
            String value = p.getProperty(key);
            assertThat(value).as(key).doesNotContain("<").doesNotContain(">").doesNotContain("&");
            if (value.contains("{0}")) {
                assertThat(value).as(key + ": MessageFormat would drop the apostrophe").doesNotContain("'");
            }
        }
    }
}
