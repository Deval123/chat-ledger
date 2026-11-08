package dev.chatledger.payments;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the database itself refuses (V015), whoever sends the row, and what the application role may and may not do with the
 * payment table. The constraints run as the schema owner so a refusal is the constraint and not a privilege.
 *
 * <p>Requirements: 2, 9. Task 5.
 */
class PaymentConstraintsIT {

    private static final PaymentFixture F = new PaymentFixture();
    private final UUID tenant = F.tenant();

    /** A row builder: a valid RECEIVED payment, with named changes. */
    private static final class Row {
        String externalId = "ext-" + UUID.randomUUID();
        String provider = "PROVIDER_A";
        String templateId = "provider-a-received";
        String txId = "TX" + UUID.randomUUID().toString().substring(0, 8);
        Long amount = 5000L;
        String currency = "XAF";
        String senderName = "Ana K.";
        String hash = "a".repeat(64);
        String last4 = "1234";
        String reference = null;
        String status = "RECEIVED";
        String rawText = "raw";
        UUID duplicateOf = null;
        boolean paidAt = true;

        Row with(java.util.function.Consumer<Row> change) {
            change.accept(this);
            return this;
        }
    }

    private static Row ok() {
        return new Row();
    }

    private void insert(Row r) throws SQLException {
        try (Connection c = PaymentFixture.DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO payment(id, tenant_id, external_id, provider, template_id, tx_id, amount_minor, currency, sender_name, "
                             + "sender_phone_hash, sender_last4, reference, paid_at, status, raw_text, duplicate_of) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + (r.paidAt ? "now()" : "NULL") + ", ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setString(3, r.externalId);
            ps.setString(4, r.provider);
            ps.setString(5, r.templateId);
            ps.setString(6, r.txId);
            if (r.amount == null) {
                ps.setNull(7, Types.BIGINT);
            } else {
                ps.setLong(7, r.amount);
            }
            ps.setString(8, r.currency);
            ps.setString(9, r.senderName);
            ps.setString(10, r.hash);
            ps.setString(11, r.last4);
            ps.setString(12, r.reference);
            ps.setString(13, r.status);
            ps.setString(14, r.rawText);
            ps.setObject(15, r.duplicateOf);
            ps.executeUpdate();
        }
    }

    private void unparsed(String externalId, String rawText, String currency) throws SQLException {
        insert(ok().with(r -> {
            r.status = "UNPARSED";
            r.externalId = externalId;
            r.rawText = rawText;
            r.currency = currency;
            r.provider = null;
            r.templateId = null;
            r.txId = null;
            r.amount = null;
            r.senderName = null;
            r.hash = null;
            r.last4 = null;
            r.paidAt = false;
        }));
    }

    private void refuses(Row row, String constraint) {
        assertThatThrownBy(() -> insert(row)).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23514");
            assertThat(e.getMessage()).contains(constraint);
        });
    }

    private UUID original() throws SQLException {
        Row r = ok();
        insert(r);
        try (Connection c = PaymentFixture.DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM payment WHERE tenant_id = ? AND external_id = ?")) {
            ps.setObject(1, tenant);
            ps.setString(2, r.externalId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    // ── By status ──────────────────────────────────────────────────────────

    @Test
    void an_unparsed_payment_holds_nothing_but_the_text_and_the_currency() throws SQLException {
        unparsed("ext-" + UUID.randomUUID(), "any text at all", "XAF");
        // Each field that a status of UNPARSED must not carry.
        refuses(ok().with(r -> {
            r.status = "UNPARSED";
        }), "chk_payment_by_status");
        refuses(ok().with(r -> {
            r.status = "UNPARSED";
            r.provider = null;
            r.amount = null;
            r.paidAt = false;
            r.templateId = null;
            r.senderName = null;
            r.hash = null;
            r.last4 = null;
        }), "chk_payment_by_status");   // a transaction id left on an unparsed row
        refuses(ok().with(r -> {
            r.status = "UNPARSED";
            r.provider = null;
            r.amount = null;
            r.txId = null;
            r.paidAt = false;
            r.templateId = null;
            r.senderName = null;
            r.hash = null;
            r.last4 = null;
            r.reference = "A-1";
        }), "chk_payment_by_status");
    }

    @Test
    void every_other_status_has_a_provider_an_amount_in_range_and_a_time() throws SQLException {
        insert(ok());
        insert(ok().with(r -> r.amount = 1L));
        insert(ok().with(r -> r.amount = 1_000_000_000_000L));
        for (long bad : new long[]{0, -1, 1_000_000_000_001L, Long.MAX_VALUE}) {
            refuses(ok().with(r -> r.amount = bad), "chk_payment_by_status");
        }
        refuses(ok().with(r -> r.amount = null), "chk_payment_by_status");
        refuses(ok().with(r -> r.provider = null), "chk_payment_by_status");
        refuses(ok().with(r -> r.paidAt = false), "chk_payment_by_status");
    }

    @Test
    void duplicate_points_at_a_payment_received_has_a_transaction_id_unverified_has_none() throws SQLException {
        UUID first = original();
        insert(ok().with(r -> {
            r.status = "DUPLICATE";
            r.duplicateOf = first;
        }));
        refuses(ok().with(r -> r.status = "DUPLICATE"), "chk_payment_status_links");   // no link
        refuses(ok().with(r -> r.duplicateOf = first), "chk_payment_status_links");    // a link on a RECEIVED row
        refuses(ok().with(r -> {
            r.status = "DUPLICATE";
            r.duplicateOf = first;
            r.txId = null;
        }), "chk_payment_status_links");
        refuses(ok().with(r -> r.txId = null), "chk_payment_status_links");              // RECEIVED without a tx id
        insert(ok().with(r -> {
            r.status = "UNVERIFIED";
            r.txId = null;
            r.hash = null;
        }));
        refuses(ok().with(r -> r.status = "UNVERIFIED"), "chk_payment_status_links");   // UNVERIFIED with a tx id
    }

    // ── Text columns ───────────────────────────────────────────────────────

    @Test
    void external_ids_follow_the_format() throws SQLException {
        insert(ok().with(r -> r.externalId = "a".repeat(128)));
        insert(ok().with(r -> r.externalId = "A-1.b_2:c"));
        for (String bad : new String[]{"a".repeat(129), "", "has space", "semi;colon", "quote'", "new\nline", "caf\u00E9", "../x"}) {
            refuses(ok().with(r -> r.externalId = bad), "chk_payment_external_id");
        }
    }

    @Test
    void currency_is_three_capital_letters() {
        for (String bad : new String[]{"XA", "xaf", "X1F", " XA", "\u00C9UR"}) {
            assertThatThrownBy(() -> insert(ok().with(r -> r.currency = bad))).isInstanceOfSatisfying(SQLException.class,
                    e -> assertThat(e.getSQLState()).isEqualTo("23514"));
        }
    }

    @Test
    void transaction_id_provider_template_and_reference_follow_their_formats() throws SQLException {
        insert(ok().with(r -> r.txId = "A".repeat(40)));
        insert(ok().with(r -> r.txId = "ab-12.3:_"));
        for (String bad : new String[]{"ABCDE", "A".repeat(41), "has space", "semi;colon", "TX'1234", "../etc/x", "caf\u00E9123"}) {
            refuses(ok().with(r -> r.txId = bad), "chk_payment_tx_id");
        }
        for (String bad : new String[]{"provider_a", "A B", "", "A".repeat(33), "P-A"}) {
            refuses(ok().with(r -> r.provider = bad), "chk_payment_provider");
        }
        for (String bad : new String[]{"Provider-A", "a b", "", "a".repeat(65), "a_b"}) {
            refuses(ok().with(r -> r.templateId = bad), "chk_payment_template_id");
        }
        insert(ok().with(r -> r.reference = "A-1:2.b_c"));
        insert(ok().with(r -> r.reference = "a".repeat(64)));
        for (String bad : new String[]{"a b", "", "a".repeat(65), "x;y", "../etc"}) {
            refuses(ok().with(r -> r.reference = bad), "chk_payment_reference");
        }
    }

    @Test
    void sender_name_hash_and_last_four_follow_their_rules() throws SQLException {
        insert(ok().with(r -> r.senderName = "Al"));
        insert(ok().with(r -> r.senderName = "A".repeat(40)));
        for (String bad : new String[]{"A", "", "A".repeat(41)}) {
            refuses(ok().with(r -> r.senderName = bad), "chk_payment_sender_name");
        }
        for (String badHash : new String[]{"A".repeat(64), "a".repeat(63), "a".repeat(65), "9990000001234"}) {
            refuses(ok().with(r -> r.hash = badHash), "chk_payment_sender");
        }
        for (String badLast4 : new String[]{"12ab", "123", ""}) {
            refuses(ok().with(r -> r.last4 = badLast4), "chk_payment_sender");
        }
        // CHAR(4) refuses five characters before any check runs.
        assertThatThrownBy(() -> insert(ok().with(r -> r.last4 = "12345"))).isInstanceOf(SQLException.class);
        refuses(ok().with(r -> r.last4 = null), "chk_payment_sender");   // a hash always comes with its last four
        insert(ok().with(r -> {
            r.hash = null;
            r.last4 = "9876";
        }));
        insert(ok().with(r -> {
            r.hash = null;
            r.last4 = null;
        }));
    }

    @Test
    void raw_text_is_one_to_one_thousand_characters_without_control_characters() throws SQLException {
        insert(ok().with(r -> r.rawText = "line one\nline\ttwo\r\n \u00E9 \uD83D\uDE00 \u202E"));
        insert(ok().with(r -> r.rawText = "a".repeat(1000)));
        for (String bad : new String[]{"", "a".repeat(1001), "a\u0001b", "a\u0007b", "a\u001Bb", "a\u007Fb", "a\u0085b", "a\u009Fb"}) {
            refuses(ok().with(r -> r.rawText = bad), "chk_payment_raw_text");
        }
        // PostgreSQL text cannot hold NUL at all: the driver or server refuses it before any constraint.
        assertThatThrownBy(() -> insert(ok().with(r -> r.rawText = "a\u0000b"))).isInstanceOf(SQLException.class);
    }

    // ── Uniqueness ─────────────────────────────────────────────────────────

    @Test
    void the_external_id_is_unique_per_tenant() throws SQLException {
        unparsed("same", "raw", "XAF");
        assertThatThrownBy(() -> unparsed("same", "raw", "XAF")).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23505");
            assertThat(e.getMessage()).contains("uq_payment_external_per_tenant");
        });
    }

    @Test
    void a_transaction_id_has_one_original_but_any_number_of_linked_duplicates() throws SQLException {
        Row first = ok();
        insert(first);
        UUID id;
        try (Connection c = PaymentFixture.DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM payment WHERE tenant_id = ? AND external_id = ?")) {
            ps.setObject(1, tenant);
            ps.setString(2, first.externalId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                id = rs.getObject(1, UUID.class);
            }
        }
        // Control: two duplicates of the same transaction id are accepted.
        insert(ok().with(r -> {
            r.status = "DUPLICATE";
            r.duplicateOf = id;
            r.txId = first.txId;
        }));
        insert(ok().with(r -> {
            r.status = "DUPLICATE";
            r.duplicateOf = id;
            r.txId = first.txId;
        }));
        // A second non-duplicate row with the same provider and transaction id is refused, whatever its status.
        assertThatThrownBy(() -> insert(ok().with(r -> r.txId = first.txId))).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23505");
            assertThat(e.getMessage()).contains("uq_payment_txid_original");
        });
        // The same id in another case is the same transaction: a second original is refused by the index.
        assertThatThrownBy(() -> insert(ok().with(r -> r.txId = first.txId.toLowerCase()))).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23505");
            assertThat(e.getMessage()).contains("uq_payment_txid_original");
        });
        // Another provider with the same id is another transaction.
        insert(ok().with(r -> {
            r.provider = "PROVIDER_B";
            r.txId = first.txId;
        }));
    }

    // ── What the application role may do ───────────────────────────────────

    private void asApp(String sql) throws SQLException {
        try (Connection c = PaymentFixture.DB.appDataSource().getConnection(); var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void the_application_role_can_insert_payments_through_the_service_but_cannot_update_or_delete_them() {
        assertThat(F.ingest(tenant, PaymentFixture.id(), F.sms(PaymentFixture.txId())).stored()).isTrue();
        // The application role may change a status since V016 (UPDATE (status), tested in MatchConstraintsIT with every other column), but
        // no other column and never delete.
        for (String sql : new String[]{"UPDATE payment SET raw_text = raw_text", "UPDATE payment SET amount_minor = 1", "UPDATE payment SET tx_id = tx_id",
                "DELETE FROM payment"}) {
            assertThatThrownBy(() -> asApp(sql)).as(sql).isInstanceOfSatisfying(SQLException.class, e -> {
                assertThat(e.getSQLState()).isEqualTo("42501");
                assertThat(e.getMessage()).contains("permission denied for table payment");
            });
        }
    }

    @Test
    void the_application_role_holds_exactly_select_and_insert_on_payments() throws SQLException {
        try (Connection c = PaymentFixture.DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT has_table_privilege('chatledger_app', 'payment', ?)")) {
            for (String granted : new String[]{"SELECT", "INSERT"}) {
                ps.setString(1, granted);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBoolean(1)).as(granted).isTrue();
                }
            }
            for (String refused : new String[]{"UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                ps.setString(1, refused);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBoolean(1)).as(refused).isFalse();
                }
            }
        }
        assertThatThrownBy(() -> asApp("TRUNCATE payment")).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void the_provisioner_login_has_no_privilege_on_payments() {
        assertThatThrownBy(() -> {
            try (Connection c = PaymentFixture.DB.provisionerDataSource().getConnection(); var s = c.createStatement()) {
                s.execute("SELECT 1 FROM payment");
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }
}
