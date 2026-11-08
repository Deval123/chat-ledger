package dev.chatledger.matching;

import dev.chatledger.board.BoardRepository;
import dev.chatledger.board.BoardRepository.Column;
import dev.chatledger.board.ExportRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant A cannot read tenant B through the board and export repositories, by id or by listing. The first test of each pair is named in
 * {@code IsolationRegistry.REPOSITORY_CONTRACT_TESTS}; the second runs the same statements as the schema owner, which row-level security does
 * not bind, so only each statement's own {@code tenant_id} predicate scopes the result. Every check has a positive control.
 *
 * <p>Requirement: 9.1. Task 8.
 */
class BoardRepositoryIsolationIT {

    private static final Instant FROM = Instant.now().minus(1, ChronoUnit.DAYS);
    private static final Instant TO = Instant.now().plus(1, ChronoUnit.DAYS);

    private static final class Two {
        final MatchWorld a = new MatchWorld();
        final MatchWorld b = new MatchWorld();
        final UUID customerB = b.customer("Isolation Bee", "cd".repeat(32), "4321");
        final UUID orderB = b.order(5000, "PAID", "B-1", customerB);
        final UUID paymentB = b.payment(5000, "MATCHED");
        final UUID matchB = b.activeMatch(orderB, paymentB, "AUTO", 5000, 0);
        final UUID openB = b.order(7000, "PENDING_PAYMENT", "B-2", customerB);
        final UUID unparsedB = b.orderWithoutTotal("UNPARSED");
        final UUID reviewB = b.orderWithoutTotal("NEEDS_REVIEW");
        final UUID duplicateB = b.duplicateOf(paymentB, 5000);
        final UUID orderA = a.order(3000, "PENDING_PAYMENT", "A-1", null);
        final UUID paymentA = a.payment(3000, "RECEIVED");

        Two() {
            b.run("INSERT INTO flag(id, tenant_id, subject_type, subject_id, code, reason) VALUES (?, ?, 'ORDER', ?, 'TEST', 'synthetic flag')",
                    UUID.randomUUID(), b.tenant, openB);
        }
    }

    private static List<UUID> ids(BoardRepository.Page page) {
        return page.cards().stream().map(BoardRepository.Card::id).toList();
    }

    private static void assertBoardScopedTo(BoardRepository board, Two t) {
        // Controls: tenant B sees its own items, each in exactly one column.
        assertThat(ids(board.column(t.b.tenant, Column.MATCHED, null, 20))).containsExactly(t.orderB);
        assertThat(ids(board.column(t.b.tenant, Column.UNPARSED, null, 20))).containsExactly(t.unparsedB);
        assertThat(ids(board.column(t.b.tenant, Column.REVIEW, null, 20))).containsExactly(t.reviewB);
        assertThat(ids(board.column(t.b.tenant, Column.FLAGGED, null, 20))).containsExactlyInAnyOrder(t.openB, t.duplicateB);
        assertThat(ids(board.column(t.b.tenant, Column.AWAITING, null, 20))).isEmpty();
        assertThat(board.counts(t.b.tenant)).containsEntry(Column.FLAGGED, 2L).containsEntry(Column.MATCHED, 1L);
        assertThat(board.order(t.b.tenant, t.orderB, false)).isPresent();
        assertThat(board.order(t.b.tenant, t.orderB, false).orElseThrow().matches()).hasSize(1);
        assertThat(board.payment(t.b.tenant, t.paymentB, false)).isPresent();
        assertThat(board.order(t.b.tenant, t.openB, false).orElseThrow().flags()).hasSize(1);
        assertThat(board.summaryCounts(t.b.tenant, FROM, TO).orders()).isEqualTo(3);
        assertThat(board.summaryAmounts(t.b.tenant, FROM, TO).outstandingMinor()).isEqualTo(7000);
        assertThat(board.tenant(t.b.tenant)).isPresent();
        // Tenant A asks for B's rows by their exact ids and by listing.
        assertThat(board.order(t.a.tenant, t.orderB, true)).isEmpty();
        assertThat(board.order(t.a.tenant, t.openB, true)).isEmpty();
        assertThat(board.payment(t.a.tenant, t.paymentB, true)).isEmpty();
        assertThat(board.payment(t.a.tenant, t.duplicateB, true)).isEmpty();
        assertThat(ids(board.column(t.a.tenant, Column.MATCHED, null, 20))).isEmpty();
        assertThat(ids(board.column(t.a.tenant, Column.FLAGGED, null, 20))).isEmpty();
        assertThat(ids(board.column(t.a.tenant, Column.UNPARSED, null, 20))).isEmpty();
        assertThat(ids(board.column(t.a.tenant, Column.AWAITING, null, 20))).containsExactly(t.orderA);
        assertThat(ids(board.column(t.a.tenant, Column.REVIEW, null, 20))).containsExactly(t.paymentA);
        assertThat(board.counts(t.a.tenant)).containsEntry(Column.FLAGGED, 0L).containsEntry(Column.AWAITING, 1L);
        assertThat(board.summaryCounts(t.a.tenant, FROM, TO).orders()).isEqualTo(1);
        assertThat(board.summaryCounts(t.a.tenant, FROM, TO).flagged()).isZero();
        assertThat(board.summaryAmounts(t.a.tenant, FROM, TO).outstandingMinor()).isEqualTo(3000);
    }

    private static void assertExportScopedTo(ExportRepository exports, Two t) {
        assertThat(exports.countOrders(t.b.tenant, FROM, TO)).isEqualTo(4);
        assertThat(exports.countPayments(t.b.tenant, FROM, TO)).isEqualTo(2);
        assertThat(exports.countLedger(t.b.tenant, FROM, TO)).isEqualTo(2);
        assertThat(exports.countOrders(t.a.tenant, FROM, TO)).isEqualTo(1);
        assertThat(exports.countPayments(t.a.tenant, FROM, TO)).isEqualTo(1);
        assertThat(exports.countLedger(t.a.tenant, FROM, TO)).isZero();
        List<UUID> seen = new ArrayList<>();
        exports.orders(t.a.tenant, FROM, TO, 100, r -> seen.add(r.id()));
        exports.payments(t.a.tenant, FROM, TO, 100, r -> seen.add(r.id()));
        exports.ledger(t.a.tenant, FROM, TO, 100, r -> seen.add(r.txnId()));
        assertThat(seen).containsExactlyInAnyOrder(t.orderA, t.paymentA);
        List<UUID> theirs = new ArrayList<>();
        exports.orders(t.b.tenant, FROM, TO, 100, r -> theirs.add(r.id()));
        exports.payments(t.b.tenant, FROM, TO, 100, r -> theirs.add(r.id()));
        assertThat(theirs).contains(t.orderB, t.openB, t.paymentB, t.duplicateB).doesNotContain(t.orderA, t.paymentA);
    }

    @Test
    void another_tenants_board_cannot_be_read() {
        assertBoardScopedTo(new BoardRepository(MatchWorld.DB.appDataSource()), new Two());
    }

    @Test
    void the_board_statements_scope_by_tenant_by_themselves_even_where_row_level_security_does_not_apply() {
        assertBoardScopedTo(new BoardRepository(MatchWorld.DB.migrateDataSource()), new Two());
    }

    @Test
    void another_tenants_rows_cannot_be_exported() {
        assertExportScopedTo(new ExportRepository(MatchWorld.DB.appDataSource()), new Two());
    }

    @Test
    void the_export_statements_scope_by_tenant_by_themselves_even_where_row_level_security_does_not_apply() {
        assertExportScopedTo(new ExportRepository(MatchWorld.DB.migrateDataSource()), new Two());
    }

    @Test
    void a_stream_stops_at_its_cap_and_an_unknown_tenant_sees_nothing() {
        Two t = new Two();
        ExportRepository exports = new ExportRepository(MatchWorld.DB.appDataSource());
        List<UUID> seen = new ArrayList<>();
        exports.orders(t.b.tenant, FROM, TO, 2, r -> seen.add(r.id()));
        assertThat(seen).hasSize(2);
        BoardRepository board = new BoardRepository(MatchWorld.DB.appDataSource());
        UUID nobody = UUID.randomUUID();
        assertThat(board.tenant(nobody)).isEmpty();
        assertThat(board.counts(nobody)).containsEntry(Column.AWAITING, 0L);
        assertThat(exports.countOrders(nobody, FROM, TO)).isZero();
    }

    @Test
    void a_page_has_a_cursor_that_leads_to_the_rest_without_repeats() {
        MatchWorld w = new MatchWorld();
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        List<UUID> made = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            made.add(w.order(1000 + i, "PENDING_PAYMENT", "P-" + i, null, "XAF", base.plusSeconds(i)));
        }
        BoardRepository board = new BoardRepository(MatchWorld.DB.appDataSource());
        BoardRepository.Page first = board.column(w.tenant, Column.AWAITING, null, 2);
        assertThat(first.next()).isNotNull();
        BoardRepository.Page second = board.column(w.tenant, Column.AWAITING, BoardRepository.Cursor.parse(first.next().format()).orElseThrow(), 2);
        BoardRepository.Page third = board.column(w.tenant, Column.AWAITING, BoardRepository.Cursor.parse(second.next().format()).orElseThrow(), 2);
        assertThat(third.next()).isNull();
        List<UUID> all = new ArrayList<>(ids(first));
        all.addAll(ids(second));
        all.addAll(ids(third));
        assertThat(all).containsExactly(made.get(4), made.get(3), made.get(2), made.get(1), made.get(0));
    }

    private static void flag(MatchWorld w, UUID subject) {
        w.run("INSERT INTO flag(id, tenant_id, subject_type, subject_id, code, reason) VALUES (?, ?, 'X', ?, 'TEST', 'synthetic flag')", UUID.randomUUID(), w.tenant, subject);
    }

    @Test
    void each_item_is_in_exactly_one_column_by_the_precedence_rules() {
        MatchWorld w = new MatchWorld();
        Map<UUID, Column> expected = new java.util.LinkedHashMap<>();
        UUID unparsedFlagged = w.orderWithoutTotal("UNPARSED");
        flag(w, unparsedFlagged);
        expected.put(unparsedFlagged, Column.UNPARSED);                 // unread beats flagged
        expected.put(w.unparsedPayment(), Column.UNPARSED);
        UUID reviewFlagged = w.orderWithoutTotal("NEEDS_REVIEW");
        flag(w, reviewFlagged);
        expected.put(reviewFlagged, Column.FLAGGED);                    // flagged beats review
        UUID receivedFlagged = w.payment(100, "RECEIVED");
        flag(w, receivedFlagged);
        expected.put(receivedFlagged, Column.FLAGGED);
        UUID paidFlagged = w.order(100, "PAID", null, null);
        flag(w, paidFlagged);
        expected.put(paidFlagged, Column.FLAGGED);                      // flagged beats matched
        UUID awaitingFlagged = w.order(100, "PENDING_PAYMENT", null, null);
        flag(w, awaitingFlagged);
        expected.put(awaitingFlagged, Column.FLAGGED);                  // flagged beats awaiting
        UUID asideFlagged = w.payment(100, "UNMATCHED");
        flag(w, asideFlagged);
        expected.put(asideFlagged, Column.FLAGGED);                     // flagged beats set aside
        expected.put(w.payment(100, "UNVERIFIED"), Column.FLAGGED);     // by status
        UUID original = w.payment(100, "MATCHED");
        expected.put(w.duplicateOf(original, 100), Column.FLAGGED);
        expected.put(w.orderWithoutTotal("NEEDS_REVIEW"), Column.REVIEW);
        expected.put(w.payment(100, "RECEIVED"), Column.REVIEW);
        expected.put(w.order(100, "PAID", null, null), Column.MATCHED);
        expected.put(w.order(100, "PENDING_PAYMENT", null, null), Column.AWAITING);
        expected.put(w.order(100, "PARTIALLY_PAID", null, null), Column.AWAITING);
        expected.put(w.payment(100, "UNMATCHED"), Column.ASIDE);
        for (javax.sql.DataSource ds : new javax.sql.DataSource[]{MatchWorld.DB.appDataSource(), MatchWorld.DB.migrateDataSource()}) {
            BoardRepository board = new BoardRepository(ds);
            Map<UUID, Column> found = new java.util.LinkedHashMap<>();
            for (Column column : Column.values()) {
                for (UUID id : ids(board.column(w.tenant, column, null, 50))) {
                    assertThat(found.put(id, column)).as(id + " is in one column only").isNull();
                }
            }
            found.remove(original);   // the matched payment is shown on its order, not as a card
            assertThat(found).isEqualTo(expected);
            Map<Column, Long> counts = new java.util.EnumMap<>(Column.class);
            for (Column column : Column.values()) {
                counts.put(column, expected.values().stream().filter(c -> c == column).count());
            }
            assertThat(board.counts(w.tenant)).isEqualTo(counts);
        }
    }

    @Test
    void a_page_holds_at_least_one_and_at_most_fifty_cards() {
        MatchWorld w = new MatchWorld();
        for (int i = 0; i < 52; i++) {
            w.order(100 + i, "PENDING_PAYMENT", null, null);
        }
        BoardRepository board = new BoardRepository(MatchWorld.DB.appDataSource());
        assertThat(board.column(w.tenant, Column.AWAITING, null, 500).cards()).hasSize(50);
        assertThat(board.column(w.tenant, Column.AWAITING, null, 500).next()).isNotNull();
        assertThat(board.column(w.tenant, Column.AWAITING, null, 0).cards()).hasSize(1);
        assertThat(board.column(w.tenant, Column.AWAITING, null, -5).cards()).hasSize(1);
    }
}
