package dev.chatledger.matching;

import dev.chatledger.flags.FlagRepository;
import dev.chatledger.flags.SuspicionWriter;
import dev.chatledger.orders.IngestionResult;
import dev.chatledger.payments.PaymentIngestionResult;
import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.Suspicion;
import dev.chatledger.suspicion.SuspicionConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.count;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payment and order rules through the real ingestion services and database: each rule raises its flag, a payment and its flags (an order
 * and its flag) commit together or not at all, and a retry adds nothing because ingestion is idempotent by (tenant, external id) and a flag is
 * unique per rule and subject. Synthetic names and numbers only.
 *
 * <p>Requirements: 5, 1.3, 2.3. Task 7.
 */
class FlagIngestionIT {
    private final MatchingFixture f = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");

    private List<String> flagsOf(UUID paymentOrOrder) {
        return MatchingFixture.rows("SELECT code FROM flag WHERE tenant_id = ? AND (payment_id = '" + paymentOrOrder + "' OR (subject_type = 'ORDER' AND order_id = '"
                + paymentOrOrder + "')) ORDER BY code", tenant);
    }

    private String sms(long amount, String name, String phone, String tx, Duration ago) {
        return "You received " + amount + " XAF from " + name + " (" + phone + "). Txn " + tx + ". " + f.date(ago);
    }

    @Test
    void r1_a_repeated_transaction_id_is_flagged_on_the_repeat() {
        String tx = MatchingFixture.tx();
        PaymentIngestionResult first = f.payments.ingest(tenant, seller, id(), sms(5000, "Ana Kamga", PHONE, tx, Duration.ofMinutes(5)));
        PaymentIngestionResult again = f.payments.ingest(tenant, seller, id(), sms(5000, "Ana Kamga", PHONE, tx, Duration.ofMinutes(5)));
        assertThat(again.outcome()).isEqualTo(PaymentIngestionResult.Outcome.CREATED_DUPLICATE_TX);
        assertThat(flagsOf(first.paymentId())).isEmpty();
        assertThat(flagsOf(again.paymentId())).containsExactly("DUPLICATE_TX");
    }

    @Test
    void r2_a_forward_without_a_transaction_id_is_flagged() {
        PaymentIngestionResult r = f.payments.ingest(tenant, seller, id(), f.forwarded(5000, "Ana Kamga", "1234"));
        assertThat(r.outcome()).isEqualTo(PaymentIngestionResult.Outcome.CREATED_UNVERIFIED);
        assertThat(flagsOf(r.paymentId())).containsExactly("NO_PROVIDER_ID");
    }

    @Test
    void r6_the_fourth_payment_from_one_phone_in_thirty_minutes_is_a_burst() {
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(f.payments.ingest(tenant, seller, id(), sms(1000 + i, "Ana Kamga", PHONE, MatchingFixture.tx(), Duration.ofMinutes(20 - 5L * i))).paymentId());
        }
        assertThat(flagsOf(ids.get(0))).isEmpty();
        assertThat(flagsOf(ids.get(2))).isEmpty();
        assertThat(flagsOf(ids.get(3))).containsExactly("SENDER_BURST");
        // Another phone is another sender.
        UUID other = f.payments.ingest(tenant, seller, id(), sms(900, "Ana Kamga", "+999 000 000 9999", MatchingFixture.tx(), Duration.ofMinutes(1))).paymentId();
        assertThat(flagsOf(other)).isEmpty();
    }

    @Test
    void r6_repeats_of_one_payment_are_not_counted_as_more_payments() {
        String tx = MatchingFixture.tx();
        f.payments.ingest(tenant, seller, id(), sms(1000, "Ana Kamga", PHONE, tx, Duration.ofMinutes(20)));
        UUID repeat1 = f.payments.ingest(tenant, seller, id(), sms(1000, "Ana Kamga", PHONE, tx, Duration.ofMinutes(20))).paymentId();
        UUID repeat2 = f.payments.ingest(tenant, seller, id(), sms(1000, "Ana Kamga", PHONE, tx, Duration.ofMinutes(20))).paymentId();
        assertThat(MatchingFixture.status("payment", repeat1)).isEqualTo("DUPLICATE");
        assertThat(MatchingFixture.status("payment", repeat2)).isEqualTo("DUPLICATE");
        UUID next = f.payments.ingest(tenant, seller, id(), sms(1001, "Ana Kamga", PHONE, MatchingFixture.tx(), Duration.ofMinutes(5))).paymentId();
        assertThat(flagsOf(next)).as("two payments, not four").isEmpty();
    }

    @Test
    void r6_many_payments_paid_after_this_one_do_not_hide_the_earlier_ones() {
        for (int i = 0; i < 3; i++) {
            f.payments.ingest(tenant, seller, id(), sms(1000 + i, "Ana Kamga", PHONE, MatchingFixture.tx(), Duration.ofMinutes(20 - 5L * i)));
        }
        // More than the records read at most, all paid after the next one (recorded first, paid later).
        for (int i = 0; i < dev.chatledger.suspicion.PaymentContext.MAX_SENDER_RECORDS + 1; i++) {
            f.payments.ingest(tenant, seller, id(), sms(2000 + i, "Ana Kamga", PHONE, MatchingFixture.tx(), Duration.ofMinutes(1)));
        }
        UUID late = f.payments.ingest(tenant, seller, id(), sms(900, "Ana Kamga", PHONE, MatchingFixture.tx(), Duration.ofMinutes(5))).paymentId();
        assertThat(flagsOf(late)).as("three earlier in 15 minutes").containsExactly("SENDER_BURST");
    }

    @Test
    void r9_a_payment_paid_days_before_it_was_recorded_is_flagged() {
        UUID old = f.payments.ingest(tenant, seller, id(), sms(5000, "Ana Kamga", PHONE, MatchingFixture.tx(), Duration.ofDays(4))).paymentId();
        assertThat(flagsOf(old)).containsExactly("PAID_OUTSIDE_USUAL_WINDOW");
        UUID recent = f.payments.ingest(tenant, seller, id(), sms(5000, "Bea Nkomo", "+999 000 000 2222", MatchingFixture.tx(), Duration.ofDays(2))).paymentId();
        assertThat(flagsOf(recent)).isEmpty();
    }

    @Test
    void r10_the_same_phone_under_another_name_is_flagged_on_the_payment_and_on_the_order() {
        f.payments.ingest(tenant, seller, id(), sms(1000, "Bob Synthetic", PHONE, MatchingFixture.tx(), Duration.ofHours(3)));
        UUID second = f.payments.ingest(tenant, seller, id(), sms(1000, "Ana Kamga", PHONE, MatchingFixture.tx(), Duration.ofMinutes(5))).paymentId();
        assertThat(flagsOf(second)).containsExactly("SENDER_ALIAS");

        UUID firstOrder = f.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("O-1", "Ana Kamga", "+999 000 000 7777", 500)).orderId();
        UUID secondOrder = f.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("O-2", "Bob Synthetic", "+999 000 000 7777", 500)).orderId();
        UUID thirdOrder = f.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("O-3", "A. Kamga", "+999 000 000 7777", 500)).orderId();
        assertThat(flagsOf(firstOrder)).isEmpty();
        assertThat(flagsOf(secondOrder)).containsExactly("SENDER_ALIAS");
        assertThat(flagsOf(thirdOrder)).as("an initial agrees").isEmpty();
        assertThat(MatchingFixture.rows("SELECT subject_type || ' ' || (payment_id IS NULL) FROM flag WHERE tenant_id = ? AND order_id IS NOT NULL", tenant))
                .containsExactly("ORDER true");
        // The stored customer keeps its first name; the flag is what tells the seller.
        assertThat(MatchingFixture.rows("SELECT DISTINCT cu.display_name FROM orders o JOIN customer cu ON cu.id = o.customer_id WHERE o.tenant_id = ? "
                + "AND o.order_ref IN ('O-1', 'O-2', 'O-3')", tenant)).containsExactly("Ana Kamga");
    }

    @Test
    void an_unparsed_payment_and_an_ordinary_one_raise_nothing_and_a_flag_holds_only_the_codes_sentence() {
        UUID unread = f.payments.ingest(tenant, seller, id(), "hello there, any news?").paymentId();
        UUID ordinary = f.payments.ingest(tenant, seller, id(), f.sms(5000, "Ana Kamga", PHONE, null)).paymentId();
        assertThat(flagsOf(unread)).isEmpty();
        assertThat(flagsOf(ordinary)).isEmpty();
        UUID forward = f.payments.ingest(tenant, seller, id(), f.forwarded(5000, "Secret Synthetic Name", "1234")).paymentId();
        assertThat(MatchingFixture.rows("SELECT reason FROM flag WHERE tenant_id = ? AND payment_id = '" + forward + "'", tenant))
                .containsExactly(FlagCode.NO_PROVIDER_ID.sentence());
    }

    // ── One transaction, on purpose (owner's clarification 3) ──────────────

    /** A writer whose every flag insert fails, as a broken database statement would. */
    private static SuspicionWriter failingWriter() {
        return new SuspicionWriter(new Suspicion(SuspicionConfig.defaults()), new FlagRepository() {
            @Override
            public boolean insertWithin(Connection c, UUID tenantId, Finding finding, Instant at) throws SQLException {
                throw new SQLException("injected flag failure");
            }
        }, Clock.systemUTC());
    }

    @Test
    void a_payment_whose_flag_cannot_be_written_is_not_stored_at_all_and_the_retry_stores_both() {
        MatchingFixture broken = new MatchingFixture(failingWriter(), MatchingService.StepHook.NONE, false);
        String externalId = id();
        String text = broken.forwarded(5000, "Ana Kamga", "1234");
        assertThatThrownBy(() -> broken.payments.ingest(tenant, seller, externalId, text)).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ?", tenant, null)).as("the payment rolled back with its flag").isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'PAYMENT_INGESTED'", tenant, null)).isZero();
        // The retry with the same external id is a first attempt, and stores the payment with its flag.
        PaymentIngestionResult retry = f.payments.ingest(tenant, seller, externalId, text);
        assertThat(retry.outcome()).isEqualTo(PaymentIngestionResult.Outcome.CREATED_UNVERIFIED);
        assertThat(flagsOf(retry.paymentId())).containsExactly("NO_PROVIDER_ID");
    }

    @Test
    void an_order_whose_flag_cannot_be_written_is_not_stored_at_all() {
        f.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("O-1", "Ana Kamga", "+999 000 000 7777", 500));
        MatchingFixture broken = new MatchingFixture(failingWriter(), MatchingService.StepHook.NONE, false);
        long before = count("SELECT count(*) FROM orders WHERE tenant_id = ?", tenant, null);
        assertThatThrownBy(() -> broken.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("O-2", "Bob Synthetic", "+999 000 000 7777", 500)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT count(*) FROM orders WHERE tenant_id = ?", tenant, null)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ?", tenant, null)).as("its posting rolled back too").isEqualTo(1);
    }

    @Test
    void a_retry_with_the_same_external_id_adds_no_payment_and_no_flag() {
        String externalId = id();
        String text = f.forwarded(5000, "Ana Kamga", "1234");
        PaymentIngestionResult first = f.payments.ingest(tenant, seller, externalId, text);
        PaymentIngestionResult again = f.payments.ingest(tenant, seller, externalId, text);
        assertThat(again.outcome()).isEqualTo(PaymentIngestionResult.Outcome.REPLAY);
        assertThat(again.paymentId()).isEqualTo(first.paymentId());
        assertThat(count("SELECT count(*) FROM flag WHERE tenant_id = ?", tenant, null)).isEqualTo(1);
        IngestionResult order = f.orders.ingest(tenant, seller, "order-1", MatchingFixture.orderText("O-1", "Ana Kamga", "+999 000 000 7777", 500));
        f.orders.ingest(tenant, seller, "order-2", MatchingFixture.orderText("O-2", "Bob Synthetic", "+999 000 000 7777", 500));
        f.orders.ingest(tenant, seller, "order-2", MatchingFixture.orderText("O-2", "Bob Synthetic", "+999 000 000 7777", 500));
        assertThat(order.stored()).isTrue();
        assertThat(count("SELECT count(*) FROM flag WHERE tenant_id = ? AND code = 'SENDER_ALIAS'", tenant, null)).isEqualTo(1);
    }

    @Test
    void the_same_finding_written_twice_is_one_flag() throws SQLException {
        UUID payment = f.payments.ingest(tenant, seller, id(), f.forwarded(5000, "Ana Kamga", "1234")).paymentId();
        FlagRepository flags = new FlagRepository();
        try (Connection c = MatchWorld.DB.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var set = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                set.setString(1, tenant.toString());
                set.execute();
            }
            assertThat(flags.insertWithin(c, tenant, Finding.payment(FlagCode.NO_PROVIDER_ID, payment), Instant.now())).as("already there").isFalse();
            assertThat(flags.insertWithin(c, tenant, Finding.payment(FlagCode.SENDER_BURST, payment), Instant.now())).as("another rule").isTrue();
            assertThat(flags.insertWithin(c, tenant, Finding.payment(FlagCode.SENDER_BURST, payment), Instant.now())).isFalse();
            c.commit();
        }
        assertThat(flagsOf(payment)).containsExactly("NO_PROVIDER_ID", "SENDER_BURST");
    }
}
