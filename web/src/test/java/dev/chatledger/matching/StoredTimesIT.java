package dev.chatledger.matching;

import dev.chatledger.tenancy.DbTime;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixture's clock has nanoseconds on every machine (a Linux clock does; a Mac's does not, which is how a failure on the CI machine
 * passed on the developer's). PostgreSQL keeps microseconds and ROUNDS what it is given, so a time that is stored as it is comes back
 * a microsecond later than the one the service used. Every time the matcher writes must be the clock's time cut to microseconds,
 * identical stored, returned and audited.
 *
 * <p>Requirements: 4, 10. Task 6.
 */
class StoredTimesIT {

    private final MatchingFixture f = new MatchingFixture();
    private final MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");
    private final UUID helper = MatchingFixture.user(tenant, "HELPER");

    private Instant expected() {
        Instant now = f.clock.instant();
        assertThat(now.getNano() % 1000).as("the test clock has sub-microsecond digits").isNotZero();
        return DbTime.micros(now);
    }

    private static Instant read(String sql, Object... params) {
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, OffsetDateTime.class).toInstant();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID order() {
        return quiet.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("A-1042", "Ana Kamga", PHONE, 5000)).orderId();
    }

    private UUID payment(UUID actor) {
        return quiet.payments.ingest(tenant, actor, id(), quiet.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
    }

    @Test
    void every_time_a_match_and_its_posting_write_is_the_clocks_time_cut_to_microseconds() {
        UUID order = order();
        UUID payment = payment(helper);
        f.matcher.onPaymentRecorded(tenant, payment);
        UUID suggestion = f.repository.forPayment(tenant, payment).get(0).id();
        f.clock.advance(Duration.ofSeconds(7));
        Instant at = expected();

        MatchingService.ApplyResult r = f.matcher.confirm(tenant, suggestion, seller);

        assertThat(f.repository.find(tenant, r.matchId()).orElseThrow().decidedAt()).isEqualTo(at);
        assertThat(read("SELECT t.created_at FROM ledger_txn t WHERE t.tenant_id = ? AND t.kind = 'PAYMENT_MATCHED'", tenant)).isEqualTo(at);

        f.clock.advance(Duration.ofSeconds(9));
        Instant later = expected();
        assertThat(f.matcher.unmatch(tenant, r.matchId(), seller).done()).isTrue();
        assertThat(f.repository.find(tenant, r.matchId()).orElseThrow().decidedAt()).isEqualTo(later);
        assertThat(read("SELECT t.created_at FROM ledger_txn t WHERE t.tenant_id = ? AND t.kind = 'REVERSAL'", tenant)).isEqualTo(later);
        assertThat(order).isNotNull();
    }

    @Test
    void the_time_of_a_rejection_a_manual_match_and_a_payment_set_aside_is_cut_the_same_way() {
        UUID order = order();
        UUID rejected = payment(helper);
        f.matcher.onPaymentRecorded(tenant, rejected);
        UUID suggestion = f.repository.forPayment(tenant, rejected).get(0).id();
        f.clock.advance(Duration.ofSeconds(3));
        Instant at = expected();
        assertThat(f.matcher.reject(tenant, suggestion, seller).done()).isTrue();
        assertThat(f.repository.find(tenant, suggestion).orElseThrow().decidedAt()).isEqualTo(at);

        UUID aside = payment(helper);
        f.matcher.onPaymentRecorded(tenant, aside);
        UUID open = f.repository.forPayment(tenant, aside).get(0).id();
        f.clock.advance(Duration.ofSeconds(3));
        Instant asideAt = expected();
        assertThat(f.matcher.markUnmatched(tenant, aside, seller).done()).isTrue();
        assertThat(f.repository.find(tenant, open).orElseThrow().decidedAt()).isEqualTo(asideAt);

        UUID manual = payment(seller);
        f.clock.advance(Duration.ofSeconds(3));
        Instant manualAt = expected();
        MatchingService.ApplyResult r = f.matcher.matchManually(tenant, order, manual, seller);
        assertThat(f.repository.find(tenant, r.matchId()).orElseThrow().decidedAt()).isEqualTo(manualAt);
    }

    @Test
    void an_automatic_posting_and_the_order_posting_are_stored_cut_to_microseconds_too() {
        MatchingFixture live = new MatchingFixture();
        Instant at = DbTime.micros(live.clock.instant());
        live.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("A-1042", "Ana Kamga", PHONE, 5000));
        UUID payment = live.payments.ingest(tenant, seller, id(), live.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
        assertThat(live.repository.forPayment(tenant, payment).get(0).state()).isEqualTo("AUTO");
        assertThat(read("SELECT t.created_at FROM ledger_txn t WHERE t.tenant_id = ? AND t.kind = 'PAYMENT_MATCHED'", tenant)).isEqualTo(at);
        // the order's own posting is stamped with the machine's clock at ingestion: whatever it is, it has no digit PostgreSQL would round
        assertThat(read("SELECT t.created_at FROM ledger_txn t WHERE t.tenant_id = ? AND t.kind = 'ORDER_ACCEPTED'", tenant).getNano() % 1000).isZero();
    }
}
