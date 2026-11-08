package dev.chatledger.matching;

import dev.chatledger.tenancy.TenantScope;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The statements the apply step runs inside its transaction: who recorded a payment (read from the audit trail, never from a caller),
 * the locks, the tenant on every statement (run as the schema owner, which row-level security does not bind), and that the indexes the
 * matcher relies on exist and can serve its queries.
 *
 * <p>Requirements: 3.1, 9.1. Task 6.2.
 */
class MatchRepositoryWithinIT {

    private final MatchingFixture f = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
    private final MatchRepository repository = new MatchRepository(MatchWorld.DB.appDataSource());

    // ── Who recorded a payment ─────────────────────────────────────────────

    @Test
    void the_recorder_is_read_from_the_audit_trail_of_the_payments_ingestion() {
        MatchWorld w = new MatchWorld();
        UUID seller = MatchingFixture.user(w.tenant, "SELLER");
        UUID helper = MatchingFixture.user(w.tenant, "HELPER");
        UUID bySeller = f.payments.ingest(w.tenant, seller, id(), f.sms(1000, "Ana Kamga", MatchingFixture.PHONE, null)).paymentId();
        UUID byHelper = f.payments.ingest(w.tenant, helper, id(), f.sms(1001, "Ana Kamga", MatchingFixture.PHONE, null)).paymentId();
        UUID withoutActor = f.payments.ingest(w.tenant, null, id(), f.sms(1002, "Ana Kamga", MatchingFixture.PHONE, null)).paymentId();
        UUID unknown = w.payment(1003, "RECEIVED");   // written by hand: no audit entry
        assertThat(repository.paymentCandidate(w.tenant, bySeller).orElseThrow().recorder()).isEqualTo(Recorder.SELLER);
        assertThat(repository.paymentCandidate(w.tenant, byHelper).orElseThrow().recorder()).isEqualTo(Recorder.HELPER);
        assertThat(repository.paymentCandidate(w.tenant, withoutActor).orElseThrow().recorder()).as("no actor is not the machine").isEqualTo(Recorder.HELPER);
        assertThat(repository.paymentCandidate(w.tenant, unknown).orElseThrow().recorder()).as("no trail, no trust").isEqualTo(Recorder.HELPER);
        assertThat(repository.openPaymentCandidates(w.tenant, "XAF", 100)).extracting(MatchRepository.PaymentCandidate::recorder)
                .containsExactlyInAnyOrder(Recorder.SELLER, Recorder.HELPER, Recorder.HELPER, Recorder.HELPER);
    }

    @Test
    void a_later_audit_entry_cannot_raise_the_trust_of_a_payment() {
        MatchWorld w = new MatchWorld();
        UUID seller = MatchingFixture.user(w.tenant, "SELLER");
        UUID helper = MatchingFixture.user(w.tenant, "HELPER");
        UUID payment = f.payments.ingest(w.tenant, helper, id(), f.sms(1000, "Ana Kamga", MatchingFixture.PHONE, null)).paymentId();
        // The application role may append audit rows, so an entry claiming the seller (or the machine) recorded it could be written later.
        w.run("INSERT INTO audit_log(id, tenant_id, actor, action, subject, at) VALUES (?, ?, ?, 'PAYMENT_INGESTED', ?, now() + interval '1 minute')",
                UUID.randomUUID(), w.tenant, seller, payment.toString());
        w.run("INSERT INTO audit_log(id, tenant_id, actor, action, subject, at) VALUES (?, ?, NULL, 'PAYMENT_INGESTED', ?, now() + interval '2 minutes')",
                UUID.randomUUID(), w.tenant, payment.toString());
        assertThat(repository.paymentCandidate(w.tenant, payment).orElseThrow().recorder()).isEqualTo(Recorder.HELPER);
    }

    @Test
    void another_tenants_audit_entry_for_the_same_subject_counts_for_nothing() {
        MatchWorld w = new MatchWorld();
        MatchWorld other = new MatchWorld();
        UUID helper = MatchingFixture.user(w.tenant, "HELPER");
        UUID payment = f.payments.ingest(w.tenant, helper, id(), f.sms(1000, "Ana Kamga", MatchingFixture.PHONE, null)).paymentId();
        other.run("INSERT INTO audit_log(id, tenant_id, actor, action, subject, at) VALUES (?, ?, NULL, 'PAYMENT_INGESTED', ?, now() - interval '1 hour')",
                UUID.randomUUID(), other.tenant, payment.toString());
        assertThat(repository.paymentCandidate(w.tenant, payment).orElseThrow().recorder()).isEqualTo(Recorder.HELPER);
    }

    private static void audit(MatchWorld w, UUID id, UUID actor, String action, UUID subject, String at) {
        w.run("INSERT INTO audit_log(id, tenant_id, actor, action, subject, at) VALUES (?, ?, ?, ?, ?, " + at + ")", id, w.tenant, actor, action, subject.toString());
    }

    /**
     * Row-level security already hides another tenant's rows from the application role, so these run as the schema owner, which it does
     * not bind: the recorder query must be right by itself, and the list and the lock must read the same thing.
     */
    @Test
    void the_recorder_query_is_right_without_row_level_security() throws SQLException {
        MatchWorld w = new MatchWorld();
        MatchWorld other = new MatchWorld();
        MatchRepository owner = new MatchRepository(MatchWorld.DB.migrateDataSource());
        UUID otherSeller = MatchingFixture.user(other.tenant, "SELLER");
        UUID seller = MatchingFixture.user(w.tenant, "SELLER");

        // 1. Another tenant's earlier entry for the same subject counts for nothing.
        UUID helped = f.payments.ingest(w.tenant, MatchingFixture.user(w.tenant, "HELPER"), id(), f.sms(1000, "Ana Kamga", MatchingFixture.PHONE, null)).paymentId();
        other.run("INSERT INTO audit_log(id, tenant_id, actor, action, subject, at) VALUES (?, ?, NULL, 'PAYMENT_INGESTED', ?, now() - interval '1 hour')",
                UUID.randomUUID(), other.tenant, helped.toString());
        // 2. An entry of this tenant that names a user of another tenant is not a seller of this one.
        UUID foreignActor = w.payment(1001, "RECEIVED");
        MatchWorld.forced(c -> MatchWorld.run(c, "INSERT INTO audit_log(id, tenant_id, actor, action, subject, at) VALUES (?, ?, ?, 'PAYMENT_INGESTED', ?, now())",
                UUID.randomUUID(), w.tenant, otherSeller, foreignActor.toString()));
        // 3. An entry of another kind about the same subject says nothing about who recorded it.
        UUID otherAction = w.payment(1002, "RECEIVED");
        audit(w, UUID.randomUUID(), null, "MATCH_AUTO", otherAction, "now() - interval '1 hour'");
        // 4. Two entries at the same instant: the smaller id wins, however the rows are stored.
        UUID tie = w.payment(1003, "RECEIVED");
        audit(w, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), seller, "PAYMENT_INGESTED", tie, "TIMESTAMPTZ '2030-01-01 00:00:00+00'");
        audit(w, UUID.fromString("00000000-0000-0000-0000-000000000001"), null, "PAYMENT_INGESTED", tie, "TIMESTAMPTZ '2030-01-01 00:00:00+00'");

        // 5. A later entry that claims the seller cannot replace the first one.
        UUID late = f.payments.ingest(w.tenant, MatchingFixture.user(w.tenant, "HELPER"), id(), f.sms(1006, "Ana Kamga", MatchingFixture.PHONE, null)).paymentId();
        audit(w, UUID.randomUUID(), seller, "PAYMENT_INGESTED", late, "now() + interval '1 minute'");

        assertThat(owner.paymentCandidate(w.tenant, helped).orElseThrow().recorder()).as("another tenant's entry").isEqualTo(Recorder.HELPER);
        assertThat(owner.paymentCandidate(w.tenant, foreignActor).orElseThrow().recorder()).as("an actor of another tenant").isEqualTo(Recorder.HELPER);
        assertThat(owner.paymentCandidate(w.tenant, otherAction).orElseThrow().recorder()).as("another action").isEqualTo(Recorder.HELPER);
        assertThat(owner.paymentCandidate(w.tenant, tie).orElseThrow().recorder()).as("a tie is broken by the id (the smaller id has no actor: a helper)").isEqualTo(Recorder.HELPER);
        assertThat(owner.paymentCandidate(w.tenant, late).orElseThrow().recorder()).as("the first entry stands").isEqualTo(Recorder.HELPER);
        assertThat(owner.openPaymentCandidates(w.tenant, "XAF", 100)).extracting(MatchRepository.PaymentCandidate::recorder)
                .containsOnly(Recorder.HELPER);

        // The lock reads the same recorder as the list.
        new TenantScope(MatchWorld.DB.migrateDataSource()).call(w.tenant, c -> {
            assertThat(owner.lockPayment(c, w.tenant, helped).orElseThrow().recorder()).isEqualTo(Recorder.HELPER);
            assertThat(owner.lockPayment(c, w.tenant, foreignActor).orElseThrow().recorder()).isEqualTo(Recorder.HELPER);
            assertThat(owner.lockPayment(c, w.tenant, otherAction).orElseThrow().recorder()).isEqualTo(Recorder.HELPER);
            assertThat(owner.lockPayment(c, w.tenant, tie).orElseThrow().recorder()).isEqualTo(Recorder.HELPER);
            assertThat(owner.lockPayment(c, w.tenant, late).orElseThrow().recorder()).isEqualTo(Recorder.HELPER);
            return null;
        });
        // and the seller is still trusted when the trail is honest
        UUID bySeller = f.payments.ingest(w.tenant, seller, id(), f.sms(1004, "Ana Kamga", MatchingFixture.PHONE, null)).paymentId();
        UUID withoutActor = f.payments.ingest(w.tenant, null, id(), f.sms(1005, "Ana Kamga", MatchingFixture.PHONE, null)).paymentId();
        new TenantScope(MatchWorld.DB.migrateDataSource()).call(w.tenant, c -> {
            assertThat(owner.lockPayment(c, w.tenant, bySeller).orElseThrow().recorder()).isEqualTo(Recorder.SELLER);
            assertThat(owner.lockPayment(c, w.tenant, withoutActor).orElseThrow().recorder()).as("no actor is not the machine").isEqualTo(Recorder.HELPER);
            return null;
        });
    }

    @Test
    void only_the_exact_words_seller_and_machine_raise_trust() {
        assertThat(MatchRepository.recorderOf("SELLER")).isEqualTo(Recorder.SELLER);
        assertThat(MatchRepository.recorderOf("MACHINE")).as("no stored word makes the machine before task 11").isEqualTo(Recorder.HELPER);
        assertThat(MatchRepository.recorderOf("HELPER")).isEqualTo(Recorder.HELPER);
        for (String other : new String[]{null, "", "seller", "machine", "ADMIN", "SELLER ", "OWNER"}) {
            assertThat(MatchRepository.recorderOf(other)).as(String.valueOf(other)).isEqualTo(Recorder.HELPER);
        }
    }

    // ── The locks ──────────────────────────────────────────────────────────

    private static boolean lockedByAnother(String table, UUID id) throws SQLException {
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ? FOR UPDATE NOWAIT")) {
                ps.setObject(1, id);
                ps.executeQuery();
                return false;
            } catch (SQLException e) {
                return "55P03".equals(e.getSQLState());
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void locking_a_payment_and_an_order_holds_their_rows_until_the_transaction_ends() throws SQLException {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PENDING_PAYMENT", "A-1", null);
        UUID payment = w.payment(5000, "RECEIVED");
        UUID free = w.payment(5000, "RECEIVED");
        TenantScope scope = new TenantScope(MatchWorld.DB.appDataSource());
        scope.call(w.tenant, c -> {
            assertThat(repository.lockPayment(c, w.tenant, payment)).isPresent();
            assertThat(lockedByAnother("payment", payment)).as("the payment row is locked").isTrue();
            assertThat(lockedByAnother("orders", order)).as("the order is not locked yet").isFalse();
            assertThat(repository.lockOrder(c, w.tenant, order)).isPresent();
            assertThat(lockedByAnother("orders", order)).as("the order row is locked").isTrue();
            assertThat(lockedByAnother("payment", free)).as("another payment is not").isFalse();
            return null;
        });
        assertThat(lockedByAnother("payment", payment)).as("released at the end").isFalse();
        assertThat(lockedByAnother("orders", order)).isFalse();
    }

    @Test
    void a_locked_row_comes_with_the_fields_the_apply_step_needs() throws SQLException {
        MatchWorld w = new MatchWorld();
        UUID customer = w.customer("Ana Kamga", MatchWorld.HASH, "1234");
        UUID order = w.order(5000, "PENDING_PAYMENT", "A-1", customer);
        UUID payment = w.payment(7000, "RECEIVED", true, "A-1", "Ana K.", MatchWorld.HASH, "1234", "XAF", Instant.now());
        new TenantScope(MatchWorld.DB.appDataSource()).call(w.tenant, c -> {
            MatchRepository.LockedPayment p = repository.lockPayment(c, w.tenant, payment).orElseThrow();
            assertThat(p.status()).isEqualTo("RECEIVED");
            assertThat(p.amountMinor()).isEqualTo(7000L);
            assertThat(p.currency()).isEqualTo("XAF");
            assertThat(p.hasTransactionId()).isTrue();
            assertThat(p.reference()).isEqualTo("A-1");
            assertThat(p.senderName()).isEqualTo("Ana K.");
            assertThat(p.senderPhoneHash()).isEqualTo(MatchWorld.HASH);
            assertThat(p.senderLast4()).isEqualTo("1234");
            assertThat(p.paidAt()).isNotNull();
            MatchRepository.LockedOrder o = repository.lockOrder(c, w.tenant, order).orElseThrow();
            assertThat(o.status()).isEqualTo("PENDING_PAYMENT");
            assertThat(o.totalMinor()).isEqualTo(5000L);
            assertThat(o.orderRef()).isEqualTo("A-1");
            assertThat(o.customerName()).isEqualTo("Ana Kamga");
            assertThat(o.customerPhoneHash()).isEqualTo(MatchWorld.HASH);
            assertThat(o.customerLast4()).isEqualTo("1234");
            assertThat(repository.lockPayment(c, w.tenant, UUID.randomUUID())).isEmpty();
            assertThat(repository.lockOrder(c, w.tenant, UUID.randomUUID())).isEmpty();
            return null;
        });
        UUID unparsed = w.unparsedPayment();
        UUID review = w.orderWithoutTotal("NEEDS_REVIEW");
        new TenantScope(MatchWorld.DB.appDataSource()).call(w.tenant, c -> {
            assertThat(repository.lockPayment(c, w.tenant, unparsed).orElseThrow().amountMinor()).as("wasNull").isNull();
            assertThat(repository.lockOrder(c, w.tenant, review).orElseThrow().totalMinor()).isNull();
            return null;
        });
    }

    // ── The tenant on every statement of the apply step ────────────────────

    /** Tenant B has an order that is posted and partly paid, two open orders with one code, a suggestion, and an audited payment. */
    private static final class Two {
        final MatchWorld a = new MatchWorld();
        final MatchWorld b = new MatchWorld();
        final MatchingFixture f = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        final UUID sellerB = MatchingFixture.user(b.tenant, "SELLER");
        final UUID orderB = f.orders.ingest(b.tenant, sellerB, id(), MatchingFixture.orderText("SHARED-1", "Bea Nkomo", "+999 000 000 4321", 5000)).orderId();
        final UUID twinB = f.orders.ingest(b.tenant, sellerB, id(), MatchingFixture.orderText("shared-1", "Bea Nkomo", "+999 000 000 4321", 5000)).orderId();
        final UUID paidB = b.payment(2000, "RECEIVED");
        final UUID freeB = b.payment(1000, "RECEIVED");

        UUID suggestionB;
        UUID secondSuggestionB;
        UUID thirdSuggestionB;
        UUID activeB;

        Two() {
            // 2,000 of the 5,000 are paid through the real service, so the order is posted, partly paid, and has an active match.
            f.matcher.apply(b.tenant, orderB, paidB, MatchingService.Mode.CONFIRM, sellerB);
            suggestionB = b.suggested(orderB, freeB);
            secondSuggestionB = b.suggested(twinB, freeB);
            thirdSuggestionB = b.suggested(b.order(3000, "PENDING_PAYMENT", "TH-1", null), freeB);
            activeB = f.repository.forPayment(b.tenant, paidB).get(0).id();
            b.rejected(twinB, paidB, true);   // the seller said no to this pair
        }
    }

    @Test
    void every_statement_of_the_apply_step_names_the_tenant_by_itself() throws SQLException {
        Two t = new Two();
        MatchRepository asOwner = new MatchRepository(MatchWorld.DB.migrateDataSource());
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                // Controls: for tenant B itself each statement finds its rows (the owner is not bound by row-level security).
                assertThat(asOwner.lockPayment(c, t.b.tenant, t.freeB)).isPresent();
                assertThat(asOwner.lockOrder(c, t.b.tenant, t.orderB)).isPresent();
                assertThat(asOwner.appliedWithin(c, t.b.tenant, t.orderB)).isEqualTo(2000);
                assertThat(asOwner.referenceSharedWithin(c, t.b.tenant, "SHARED-1")).isTrue();
                assertThat(asOwner.suggestionWithin(c, t.b.tenant, t.orderB, t.freeB)).contains(t.suggestionB);
                assertThat(asOwner.orderPostedWithin(c, t.b.tenant, t.orderB)).isTrue();
                assertThat(asOwner.matchWithin(c, t.b.tenant, t.suggestionB)).isPresent();
                assertThat(asOwner.lockMatchWithin(c, t.b.tenant, t.suggestionB)).isPresent();
                assertThat(asOwner.isSellerWithin(c, t.b.tenant, t.sellerB)).isTrue();
                assertThat(asOwner.wasDeclinedWithin(c, t.b.tenant, t.twinB, t.paidB)).isTrue();

                // Tenant A asks for B's rows by their exact ids.
                assertThat(asOwner.lockPayment(c, t.a.tenant, t.freeB)).isEmpty();
                assertThat(asOwner.lockOrder(c, t.a.tenant, t.orderB)).isEmpty();
                assertThat(asOwner.appliedWithin(c, t.a.tenant, t.orderB)).isZero();
                assertThat(asOwner.referenceSharedWithin(c, t.a.tenant, "SHARED-1")).isFalse();
                assertThat(asOwner.suggestionWithin(c, t.a.tenant, t.orderB, t.freeB)).isEmpty();
                assertThat(asOwner.orderPostedWithin(c, t.a.tenant, t.orderB)).isFalse();
                assertThat(asOwner.matchWithin(c, t.a.tenant, t.suggestionB)).isEmpty();
                assertThat(asOwner.lockMatchWithin(c, t.a.tenant, t.suggestionB)).isEmpty();
                assertThat(asOwner.isSellerWithin(c, t.a.tenant, t.sellerB)).as("a seller of another tenant").isFalse();
                assertThat(asOwner.wasDeclinedWithin(c, t.a.tenant, t.twinB, t.paidB)).isFalse();

                // And A cannot change B's rows: every write touches nothing.
                Instant now = Instant.now();
                assertThat(asOwner.setOrderStatusWithin(c, t.a.tenant, t.orderB, "PAID")).isZero();
                assertThat(asOwner.setPaymentStatusWithin(c, t.a.tenant, t.freeB, "MATCHED")).isZero();
                assertThat(asOwner.supersedeWithin(c, t.a.tenant, t.suggestionB, now)).isZero();
                assertThat(asOwner.supersedeForPaymentWithin(c, t.a.tenant, t.freeB, UUID.randomUUID(), now)).isZero();
                assertThat(asOwner.supersedeForOrderWithin(c, t.a.tenant, t.orderB, UUID.randomUUID(), now)).isZero();
                assertThat(asOwner.confirmSuggestionWithin(c, t.a.tenant, t.suggestionB, t.a.user, now, 1000, 0, UUID.randomUUID())).isZero();
                assertThat(asOwner.rejectWithin(c, t.a.tenant, t.suggestionB, t.a.user, now)).isZero();
                assertThat(asOwner.rejectOpenForPaymentWithin(c, t.a.tenant, t.freeB, t.a.user, now)).isZero();
                assertThat(asOwner.reverseMatchWithin(c, t.a.tenant, t.activeB, t.a.user, now)).isZero();
                // Controls for the writes: B itself can (the rows are changed, then rolled back).
                assertThat(asOwner.supersedeWithin(c, t.b.tenant, t.suggestionB, now)).isEqualTo(1);
                assertThat(asOwner.rejectWithin(c, t.b.tenant, t.thirdSuggestionB, t.sellerB, now)).isEqualTo(1);
                assertThat(asOwner.rejectWithin(c, t.b.tenant, t.thirdSuggestionB, t.sellerB, now)).as("only an open suggestion").isZero();
                assertThat(asOwner.rejectOpenForPaymentWithin(c, t.b.tenant, t.freeB, t.sellerB, now)).as("the one still open").isEqualTo(1);
                assertThat(asOwner.rejectOpenForPaymentWithin(c, t.b.tenant, t.freeB, t.sellerB, now)).as("nothing open is left for it").isZero();
                assertThat(asOwner.reverseMatchWithin(c, t.b.tenant, t.suggestionB, t.sellerB, now)).as("only an active match").isZero();
                assertThat(asOwner.reverseMatchWithin(c, t.b.tenant, t.activeB, t.sellerB, now)).isEqualTo(1);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void an_active_match_cannot_be_written_across_tenants() throws SQLException {
        Two t = new Two();
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                MatchRepository.NewActive crossing = new MatchRepository.NewActive(UUID.randomUUID(), t.orderB, t.freeB, "CONFIRMED", 1000, 0, 50, "[]",
                        null, t.a.user, Instant.now());
                assertThatThrownBy(() -> new MatchRepository(MatchWorld.DB.migrateDataSource()).insertActiveWithin(c, t.a.tenant, crossing))
                        .isInstanceOf(SQLException.class);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void rows_that_point_across_tenants_are_not_followed_even_if_the_database_let_them_exist() throws SQLException {
        MatchWorld a = new MatchWorld();
        MatchWorld b = new MatchWorld();
        UUID customerB = b.customer("Bea Nkomo", MatchWorld.HASH, "4321");
        UUID orderA = a.order(5000, "PENDING_PAYMENT", "A-1", null);
        UUID paymentB = b.payment(2000, "MATCHED");
        // Written with the foreign keys and triggers off: A's order names B's customer, and B has a match on A's order.
        MatchWorld.forced(c -> {
            MatchWorld.run(c, "UPDATE orders SET customer_id = ? WHERE id = ?", customerB, orderA);
            UUID txn = UUID.randomUUID();
            MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'LEGACY', 'X', ?)", txn, b.tenant, UUID.randomUUID());
            MatchWorld.run(c, "INSERT INTO match(id, tenant_id, order_id, payment_id, amount_minor, excess_minor, score, reasons_json, state, ledger_txn_id, decided_by, decided_at) "
                    + "VALUES (?, ?, ?, ?, 2000, 0, 90, '[]', 'CONFIRMED', ?, ?, now())", UUID.randomUUID(), b.tenant, orderA, paymentB, txn, b.user);
        });
        MatchRepository owner = new MatchRepository(MatchWorld.DB.migrateDataSource());
        OrderFacts listed = owner.openOrders(a.tenant, "XAF", 10).get(0);
        assertThat(listed.id()).isEqualTo(orderA);
        assertThat(listed.appliedMinor()).as("B's match is not A's payment").isZero();
        assertThat(listed.customerName()).as("B's customer is not A's customer").isNull();
        assertThat(owner.openOrder(a.tenant, orderA).orElseThrow().customerName()).isNull();
        new TenantScope(MatchWorld.DB.migrateDataSource()).call(a.tenant, c -> {
            assertThat(owner.lockOrder(c, a.tenant, orderA).orElseThrow().customerName()).isNull();
            assertThat(owner.appliedWithin(c, a.tenant, orderA)).isZero();
            return null;
        });
    }

    @Test
    void a_paid_order_does_not_make_a_reference_ambiguous() throws SQLException {
        MatchWorld w = new MatchWorld();
        w.order(5000, "PENDING_PAYMENT", "R-1", null);
        UUID twin = w.order(5000, "PENDING_PAYMENT", "r-1", null);
        MatchRepository owner = new MatchRepository(MatchWorld.DB.migrateDataSource());
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                assertThat(owner.referenceSharedWithin(c, w.tenant, "R-1")).as("two open orders share it").isTrue();
            } finally {
                c.rollback();
            }
        }
        MatchWorld.forced(c -> MatchWorld.run(c, "UPDATE orders SET status = 'PAID' WHERE id = ?", twin));
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                assertThat(owner.referenceSharedWithin(c, w.tenant, "R-1")).as("one open order and one that is paid").isFalse();
            } finally {
                c.rollback();
            }
        }
    }

    // ── The indexes the matcher relies on ──────────────────────────────────
    //
    // Their definitions are pinned. A plan on a nearly empty table says little and changes with whatever else the database holds (it
    // chose another index as soon as other tests had written rows), so no test claims that the planner uses an index; that is a named
    // limitation (design register item 66).

    private static String indexDef(String name) throws SQLException {
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = '" + name + "'")) {
            assertThat(rs.next()).as(name + " exists").isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void the_indexes_have_the_columns_the_queries_filter_and_sort_on() throws SQLException {
        assertThat(indexDef("idx_orders_tenant_status")).contains("ON public.orders").contains("(tenant_id, status, created_at DESC)");
        assertThat(indexDef("idx_payment_tenant_status")).contains("ON public.payment").contains("(tenant_id, status, created_at DESC)");
        assertThat(indexDef("idx_orders_ref_upper")).contains("(tenant_id, upper(order_ref))").containsPattern("WHERE \\(?order_ref IS NOT NULL");
        // only the entries the recorder lookup reads, found by tenant and subject
        assertThat(indexDef("idx_audit_log_payment_ingested")).contains("ON public.audit_log").contains("(tenant_id, subject)")
                .containsPattern("WHERE \\(?action = 'PAYMENT_INGESTED'");
    }

    @Test
    void the_system_retires_only_open_suggestions_never_a_match_that_was_decided() throws SQLException {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PENDING_PAYMENT", "A-1", null);
        UUID payment = w.payment(5000, "RECEIVED");
        UUID reversed = w.reversedMatch(order, payment, 5000);
        UUID rejected = w.rejected(order, payment, true);
        MatchRepository owner = new MatchRepository(MatchWorld.DB.migrateDataSource());
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                Instant now = Instant.now();
                // none of these is a suggestion, so none is touched (and none of them could legally become REJECTED)
                assertThat(owner.supersedeWithin(c, w.tenant, reversed, now)).isZero();
                assertThat(owner.supersedeWithin(c, w.tenant, rejected, now)).isZero();
                assertThat(owner.supersedeForPaymentWithin(c, w.tenant, payment, UUID.randomUUID(), now)).isZero();
                assertThat(owner.supersedeForOrderWithin(c, w.tenant, order, UUID.randomUUID(), now)).isZero();
                assertThat(owner.confirmSuggestionWithin(c, w.tenant, reversed, w.user, now, 5000, 0, UUID.randomUUID())).isZero();
                assertThat(owner.confirmSuggestionWithin(c, w.tenant, rejected, w.user, now, 5000, 0, UUID.randomUUID())).isZero();
                // a pair that only has decided rows has no suggestion to find
                assertThat(owner.suggestionWithin(c, w.tenant, order, payment)).isEmpty();
                // a posting of another kind that happens to carry the order's id is not the order's own posting
                MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'LEGACY', 'ORDER', ?)", UUID.randomUUID(), w.tenant, order);
                assertThat(owner.orderPostedWithin(c, w.tenant, order)).isFalse();
                // and a suggestion is retired, except the one the caller names
                UUID order2 = w.order(5000, "PENDING_PAYMENT", "A-2", null);
                UUID keep = w.suggested(order2, payment);
                UUID order3 = w.order(5000, "PENDING_PAYMENT", "A-3", null);
                UUID retire = w.suggested(order3, payment);
                assertThat(owner.supersedeForOrderWithin(c, w.tenant, order2, keep, now)).as("the new match's own row is never retired").isZero();
                assertThat(owner.supersedeForPaymentWithin(c, w.tenant, payment, keep, now)).isEqualTo(1);
                assertThat(owner.supersedeForOrderWithin(c, w.tenant, order2, retire, now)).as("keep is the order's own suggestion").isEqualTo(1);
                // and a posting that is the order's own is found for that order only
                MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'ORDER_ACCEPTED', 'ORDER', ?)", UUID.randomUUID(), w.tenant, order2);
                assertThat(owner.orderPostedWithin(c, w.tenant, order2)).isTrue();
                assertThat(owner.orderPostedWithin(c, w.tenant, order)).as("another order's posting is not this one's").isFalse();
            } finally {
                c.rollback();
            }
        }
    }
}
