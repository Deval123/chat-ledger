package dev.chatledger.matching;

import dev.chatledger.board.BoardRepository;
import dev.chatledger.board.BoardRepository.Column;
import dev.chatledger.orders.RecordActionsService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cancelled or dismissed record is on no column, in no count and in no summary figure (task 12b), even when it carries an open flag:
 * the board's CASE sends a flagged record to FLAGGED before it looks at the status, so the record must be left out before the CASE.
 */
class CancelledAndDismissedOnTheBoardIT {

    private final MatchingFixture f = new MatchingFixture();
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final BoardRepository board = new BoardRepository(MatchWorld.DB.appDataSource());
    private final RecordActionsService actions = new RecordActionsService(MatchingFixture.POOL, f.repository, f.orderRepository, f.clock);

    private void flagOrder(UUID order) {
        w.run("INSERT INTO flag(id, tenant_id, subject_type, order_id, code, reason) VALUES (?, ?, 'ORDER', ?, 'SENDER_ALIAS', 'synthetic flag')",
                UUID.randomUUID(), tenant, order);
    }

    private long onTheBoard() {
        long n = 0;
        for (Column column : Column.values()) {
            n += board.column(tenant, column, null, 50).cards().size();
        }
        return n;
    }

    private long counted() {
        return board.counts(tenant).values().stream().mapToLong(Long::longValue).sum();
    }

    private BoardRepository.SummaryCounts summary() {
        Instant now = Instant.now();
        return board.summaryCounts(tenant, now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS));
    }

    private long summaryFlagged() {
        return summary().flagged();
    }

    @Test
    void a_cancelled_order_with_an_open_flag_is_nowhere() {
        UUID order = BooksCheckIT.postedOrder(w, 4000);
        flagOrder(order);
        assertThat(counted()).isEqualTo(1);
        assertThat(board.counts(tenant).get(Column.FLAGGED)).isEqualTo(1);
        assertThat(summaryFlagged()).isEqualTo(1);
        assertThat(summary().orders()).isEqualTo(1);
        assertThat(actions.cancel(tenant, order, w.user)).isEqualTo(RecordActionsService.Outcome.DONE);
        assertThat(counted()).isZero();
        assertThat(onTheBoard()).isZero();
        assertThat(summaryFlagged()).isZero();
        assertThat(summary().orders()).as("orders recorded that day").isZero();
    }

    @Test
    void a_dismissed_order_needing_review_with_an_open_flag_is_nowhere() {
        UUID review = w.orderWithoutTotal("NEEDS_REVIEW");
        flagOrder(review);
        assertThat(counted()).isEqualTo(1);
        assertThat(summaryFlagged()).isEqualTo(1);
        assertThat(summary().orders()).isEqualTo(1);
        assertThat(actions.dismissOrder(tenant, review, w.user)).isEqualTo(RecordActionsService.Outcome.DONE);
        assertThat(counted()).isZero();
        assertThat(onTheBoard()).isZero();
        assertThat(summaryFlagged()).isZero();
        assertThat(summary().orders()).as("orders recorded that day").isZero();
    }

    @Test
    void a_dismissed_unreadable_payment_is_nowhere() {
        UUID payment = w.unparsedPayment();
        assertThat(board.counts(tenant).get(Column.UNPARSED)).isEqualTo(1);
        assertThat(onTheBoard()).isEqualTo(1);
        assertThat(actions.dismissPayment(tenant, payment, w.user)).isEqualTo(RecordActionsService.Outcome.DONE);
        assertThat(counted()).isZero();
        assertThat(onTheBoard()).isZero();
    }
}
