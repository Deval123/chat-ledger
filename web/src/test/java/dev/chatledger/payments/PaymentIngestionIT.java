package dev.chatledger.payments;

import dev.chatledger.extraction.HostileCorpus;
import dev.chatledger.extraction.HostilePayments;
import dev.chatledger.extraction.Phone;
import dev.chatledger.extraction.FailureReason;
import dev.chatledger.orders.CatalogRepository;
import dev.chatledger.orders.CustomerRepository;
import dev.chatledger.orders.IngestionEvents;
import dev.chatledger.orders.LogCapture;
import dev.chatledger.orders.OrderIngestionService;
import dev.chatledger.orders.OrderRepository;
import dev.chatledger.payments.PaymentIngestionResult.Outcome;
import dev.chatledger.security.AuditLogRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.chatledger.payments.PaymentFixture.id;
import static dev.chatledger.payments.PaymentFixture.txId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whole-service tests against PostgreSQL as the application role: what is stored for each status, duplicates, idempotency under
 * concurrency, tenant separation, the sender's phone, the shared rate limit, atomicity and the logs. Message dates come from the
 * test clock, inside the plausibility window. All names and numbers are synthetic.
 *
 * <p>Requirements: 2, 8, 9. Tasks 5.1 to 5.3.
 */
class PaymentIngestionIT {

    private static final Phone ANA = Phone.of("+999 000 000 1234").orElseThrow();

    private final PaymentFixture f = new PaymentFixture();

    // ── What is stored ─────────────────────────────────────────────────────

    @Test
    void a_confirmation_with_a_transaction_id_is_stored_as_received_with_everything_read_from_it() {
        UUID tenant = f.tenant();
        UUID actor = f.user(tenant);
        String externalId = id();
        String tx = txId();
        String text = f.sms(tx);
        PaymentIngestionResult r = f.service.ingest(tenant, actor, externalId, text);
        assertThat(r.outcome()).isEqualTo(Outcome.CREATED);

        PaymentRepository.Stored p = f.payments.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(p.id()).isEqualTo(r.paymentId());
        assertThat(p.status()).isEqualTo("RECEIVED");
        assertThat(p.provider()).isEqualTo("PROVIDER_A");
        assertThat(p.templateId()).isEqualTo("provider-a-received");
        assertThat(p.transactionId()).isEqualTo(tx);
        assertThat(p.amountMinor()).isEqualTo(5000L);
        assertThat(p.currency()).isEqualTo("XAF");
        assertThat(p.senderName()).isEqualTo("Ana K.");
        assertThat(p.senderLast4()).isEqualTo("1234");
        assertThat(p.reference()).isNull();
        assertThat(p.paidAt()).isEqualTo(f.instantOf(Duration.ofMinutes(5)));
        assertThat(p.duplicateOf()).isNull();
        assertThat(p.rawText()).as("kept exactly as received").isEqualTo(text);

        String hash = f.phoneKeys.hash(tenant, ANA);
        assertThat(p.senderPhoneHash()).isEqualTo(hash).matches("[0-9a-f]{64}").doesNotContain("9990000001234");

        List<AuditLogRepository.Entry> audit = f.audit.recent(tenant, 10);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).action()).isEqualTo("PAYMENT_INGESTED");
        assertThat(audit.get(0).subject()).isEqualTo(p.id().toString());
        assertThat(audit.get(0).actor()).isEqualTo(actor);
    }

    @Test
    void a_reference_in_the_text_is_stored_as_written_and_absent_otherwise() {
        UUID tenant = f.tenant();
        String withRef = id();
        String without = id();
        assertThat(f.ingest(tenant, withRef, f.smsWithRef(txId(), "a-1042")).outcome()).isEqualTo(Outcome.CREATED);
        assertThat(f.ingest(tenant, without, f.sms(txId())).outcome()).isEqualTo(Outcome.CREATED);
        PaymentRepository.Stored p = f.payments.findByExternalId(tenant, withRef).orElseThrow();
        assertThat(p.reference()).isEqualTo("a-1042");
        assertThat(p.templateId()).isEqualTo("provider-a-received-ref");
        assertThat(f.payments.findByExternalId(tenant, without).orElseThrow().reference()).isNull();
    }

    @Test
    void the_french_template_with_fcfa_and_a_motif_is_stored_in_the_tenants_xaf() {
        UUID tenant = f.tenant();
        String externalId = id();
        String tx = "PB" + txId().substring(2);
        String text = "Vous avez re\u00E7u 5 000 FCFA de A\u00EFcha N. (+999 000 000 1234). ID transaction : " + tx
                + ". Motif : B-77. Le " + f.date(Duration.ofMinutes(3));
        assertThat(f.ingest(tenant, externalId, text).outcome()).isEqualTo(Outcome.CREATED);
        PaymentRepository.Stored p = f.payments.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(p.provider()).isEqualTo("PROVIDER_B");
        assertThat(p.amountMinor()).isEqualTo(5000L);
        assertThat(p.currency()).isEqualTo("XAF");
        assertThat(p.senderName()).isEqualTo("A\u00EFcha N.");
        assertThat(p.reference()).isEqualTo("B-77");
    }

    @Test
    void a_forwarded_message_without_a_transaction_id_is_unverified_and_never_matched_on_a_guess() {
        UUID tenant = f.tenant();
        String first = id();
        String second = id();
        PaymentIngestionResult r = f.ingest(tenant, first, f.forwarded());
        assertThat(r.outcome()).isEqualTo(Outcome.CREATED_UNVERIFIED);
        assertThat(f.ingest(tenant, second, f.forwarded()).outcome())
                .as("no transaction id, so no duplicate detection: both are kept, both unverified")
                .isEqualTo(Outcome.CREATED_UNVERIFIED);
        PaymentRepository.Stored p = f.payments.findByExternalId(tenant, first).orElseThrow();
        assertThat(p.status()).isEqualTo("UNVERIFIED");
        assertThat(p.transactionId()).isNull();
        assertThat(p.senderLast4()).isEqualTo("1234");
        assertThat(p.senderPhoneHash()).as("only the last four digits were in the text").isNull();
        assertThat(p.amountMinor()).isEqualTo(5000L);
        assertThat(f.payments.count(tenant)).isEqualTo(2);
    }

    @Test
    void a_text_no_template_reads_is_kept_whole_as_unparsed_with_the_tenants_currency_and_nothing_else() {
        UUID tenant = f.tenant();
        String text = "Hello, I paid you 5000 francs this morning, please check. Thanks, Ana";
        String externalId = id();
        PaymentIngestionResult r = f.ingest(tenant, externalId, text);
        assertThat(r.outcome()).isEqualTo(Outcome.CREATED_UNPARSED);
        assertThat(r.reason()).isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
        PaymentRepository.Stored p = f.payments.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(p.status()).isEqualTo("UNPARSED");
        assertThat(p.rawText()).isEqualTo(text);
        assertThat(p.currency()).isEqualTo("XAF");
        assertThat(p.provider()).isNull();
        assertThat(p.amountMinor()).isNull();
        assertThat(p.transactionId()).isNull();
        assertThat(p.paidAt()).isNull();
        assertThat(p.senderName()).isNull();
        assertThat(p.senderPhoneHash()).isNull();
        assertThat(p.reference()).isNull();
        assertThat(p.templateId()).isNull();
    }

    @Test
    void a_message_in_another_currency_than_the_tenants_is_kept_unparsed_not_converted() {
        UUID tenant = f.tenant("EUR", 2);
        String externalId = id();
        PaymentIngestionResult r = f.ingest(tenant, externalId, f.sms(txId()));
        assertThat(r.outcome()).isEqualTo(Outcome.CREATED_UNPARSED);
        assertThat(f.payments.findByExternalId(tenant, externalId).orElseThrow().currency()).isEqualTo("EUR");
    }

    @Test
    void a_time_outside_the_plausibility_window_is_unparsed() {
        UUID tenant = f.tenant();
        String old = "You received 5000 XAF from Ana K. (+999 000 000 1234). Txn " + txId() + ". " + f.date(Duration.ofDays(36));
        String future = "You received 5000 XAF from Ana K. (+999 000 000 1234). Txn " + txId() + ". " + f.date(Duration.ofMinutes(-30));
        String edgeOk = "You received 5000 XAF from Ana K. (+999 000 000 1234). Txn " + txId() + ". " + f.date(Duration.ofDays(34));
        PaymentIngestionResult o = f.ingest(tenant, id(), old);
        PaymentIngestionResult fu = f.ingest(tenant, id(), future);
        assertThat(o.outcome()).isEqualTo(Outcome.CREATED_UNPARSED);
        assertThat(o.reason()).isEqualTo(FailureReason.DATE_IMPLAUSIBLE);
        assertThat(fu.outcome()).isEqualTo(Outcome.CREATED_UNPARSED);
        assertThat(fu.reason()).isEqualTo(FailureReason.DATE_IMPLAUSIBLE);
        assertThat(f.ingest(tenant, id(), edgeOk).outcome()).isEqualTo(Outcome.CREATED);
    }

    // ── Input is checked again here ────────────────────────────────────────

    @Test
    void invalid_input_is_refused_and_nothing_is_stored() {
        UUID tenant = f.tenant();
        String ok = f.sms(txId());
        Map<String, String[]> bad = Map.of(
                "nul", new String[]{id(), ok.replace("Ana", "A\u0000a")},
                "control", new String[]{id(), ok + "\u0007"},
                "lone surrogate", new String[]{id(), ok + "\uD800"},
                "too large", new String[]{id(), ok + " " + "a".repeat(1000)},
                "blank", new String[]{id(), "  \n "},
                "bad id", new String[]{"has space", ok},
                "id with sql", new String[]{"x'; DROP TABLE payment;--", ok},
                "long id", new String[]{"a".repeat(129), ok});
        bad.forEach((name, input) -> {
            PaymentIngestionResult r = f.ingest(tenant, input[0], input[1]);
            assertThat(r.outcome()).as(name).isEqualTo(Outcome.REJECTED_INPUT);
            assertThat(r.rejectReason()).as(name).isNotNull();
            assertThat(r.paymentId()).as(name).isNull();
        });
        assertThat(f.ingest(tenant, null, ok).outcome()).isEqualTo(Outcome.REJECTED_INPUT);
        assertThat(f.ingest(tenant, id(), null).outcome()).isEqualTo(Outcome.REJECTED_INPUT);
        assertThat(f.payments.count(tenant)).isZero();
        assertThat(f.audit.recent(tenant, 10)).isEmpty();
    }

    @Test
    void a_message_of_exactly_one_thousand_characters_is_accepted_and_one_more_is_refused() {
        UUID tenant = f.tenant();
        String filler = "x".repeat(1000 - 1);
        assertThat(f.ingest(tenant, id(), filler + "y").outcome()).isEqualTo(Outcome.CREATED_UNPARSED);
        assertThat(f.ingest(tenant, id(), filler + "yz").outcome()).isEqualTo(Outcome.REJECTED_INPUT);
    }

    // ── Duplicates ─────────────────────────────────────────────────────────

    @Test
    void a_repeated_transaction_id_is_kept_as_a_duplicate_linked_to_the_first_and_never_a_second_original() {
        UUID tenant = f.tenant();
        String tx = txId();
        String firstId = id();
        PaymentIngestionResult first = f.ingest(tenant, firstId, f.sms(tx));
        PaymentIngestionResult second = f.ingest(tenant, id(), f.sms(tx, 5000, "+999 000 000 1234"));
        PaymentIngestionResult third = f.ingest(tenant, id(), f.sms(tx, 9000, "+999 000 000 5678"));
        assertThat(first.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(second.outcome()).isEqualTo(Outcome.CREATED_DUPLICATE_TX);
        assertThat(third.outcome()).as("even with another amount: the first record stays the only original").isEqualTo(Outcome.CREATED_DUPLICATE_TX);
        assertThat(second.duplicateOf()).isEqualTo(first.paymentId());
        assertThat(third.duplicateOf()).isEqualTo(first.paymentId());
        assertThat(f.payments.count(tenant)).isEqualTo(3);
        assertThat(f.payments.findOriginal(tenant, "PROVIDER_A", tx)).contains(first.paymentId());
        assertThat(f.payments.findByExternalId(tenant, firstId).orElseThrow().status()).isEqualTo("RECEIVED");
    }

    @Test
    void the_duplicate_row_carries_its_own_text_and_status_and_points_at_the_original() {
        UUID tenant = f.tenant();
        String tx = txId();
        PaymentIngestionResult first = f.ingest(tenant, id(), f.sms(tx));
        String dupId = id();
        String dupText = f.sms(tx, 7000, "+999 000 000 1234");
        f.ingest(tenant, dupId, dupText);
        PaymentRepository.Stored dup = f.payments.findByExternalId(tenant, dupId).orElseThrow();
        assertThat(dup.status()).isEqualTo("DUPLICATE");
        assertThat(dup.duplicateOf()).isEqualTo(first.paymentId());
        assertThat(dup.amountMinor()).as("its own amount, for the seller to see").isEqualTo(7000L);
        assertThat(dup.rawText()).isEqualTo(dupText);
        assertThat(dup.transactionId()).isEqualTo(tx);
    }

    @Test
    void the_same_transaction_id_from_another_provider_is_not_a_duplicate() {
        UUID tenant = f.tenant();
        String tx = txId();
        assertThat(f.ingest(tenant, id(), f.sms(tx)).outcome()).isEqualTo(Outcome.CREATED);
        String french = "Vous avez re\u00E7u 5 000 FCFA de A\u00EFcha N. (+999 000 000 1234). ID transaction : " + tx + ". Le " + f.date(Duration.ofMinutes(3));
        assertThat(f.ingest(tenant, id(), french).outcome()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void a_transaction_id_is_compared_in_one_case_so_a_replay_with_the_case_changed_is_a_duplicate() {
        UUID tenant = f.tenant();
        String tx = txId();
        PaymentIngestionResult first = f.ingest(tenant, id(), f.sms(tx));
        String lowerId = id();
        String lowerText = f.sms(tx.toLowerCase());
        PaymentIngestionResult replay = f.ingest(tenant, lowerId, lowerText);
        assertThat(first.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(replay.outcome()).isEqualTo(Outcome.CREATED_DUPLICATE_TX);
        assertThat(replay.duplicateOf()).isEqualTo(first.paymentId());
        PaymentRepository.Stored dup = f.payments.findByExternalId(tenant, lowerId).orElseThrow();
        assertThat(dup.transactionId()).as("the id is kept as written").isEqualTo(tx.toLowerCase());
        assertThat(dup.rawText()).as("and so is the text").isEqualTo(lowerText);
        assertThat(f.payments.findOriginal(tenant, "PROVIDER_A", tx.toLowerCase())).contains(first.paymentId());
        assertThat(f.payments.findOriginal(tenant, "PROVIDER_A", tx)).contains(first.paymentId());
        // Mixed case too, and a different id is still a different payment.
        String mixed = tx.substring(0, 4) + tx.substring(4).toLowerCase();
        assertThat(f.ingest(tenant, id(), f.sms(mixed)).outcome()).isEqualTo(Outcome.CREATED_DUPLICATE_TX);
        assertThat(f.ingest(tenant, id(), f.sms(txId())).outcome()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void the_fold_works_in_both_directions_an_original_written_in_lower_case_is_found_by_upper_case() {
        UUID tenant = f.tenant();
        String tx = txId();
        PaymentIngestionResult first = f.ingest(tenant, id(), f.sms(tx.toLowerCase()));
        PaymentIngestionResult replay = f.ingest(tenant, id(), f.sms(tx));
        assertThat(first.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(replay.outcome()).isEqualTo(Outcome.CREATED_DUPLICATE_TX);
        assertThat(replay.duplicateOf()).isEqualTo(first.paymentId());
        assertThat(f.payments.findOriginal(tenant, "PROVIDER_A", tx)).contains(first.paymentId());
    }

    @Test
    void the_case_fold_stays_inside_the_provider_and_the_tenant() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        String tx = "PB" + txId().substring(2);
        String french = "Vous avez re\u00E7u 5 000 FCFA de A\u00EFcha N. (+999 000 000 1234). ID transaction : %s. Le " + f.date(Duration.ofMinutes(3));
        assertThat(f.ingest(a, id(), f.sms(tx)).outcome()).isEqualTo(Outcome.CREATED);
        assertThat(f.ingest(a, id(), french.formatted(tx.toLowerCase())).outcome()).as("another provider").isEqualTo(Outcome.CREATED);
        assertThat(f.ingest(b, id(), f.sms(tx.toLowerCase())).outcome()).as("another tenant").isEqualTo(Outcome.CREATED);
    }

    @Test
    void sixteen_simultaneous_messages_with_one_transaction_id_in_two_cases_make_one_original() throws Exception {
        UUID tenant = f.tenant();
        String tx = txId();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentIngestionResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String written = i % 2 == 0 ? tx : tx.toLowerCase();
            String externalId = id();
            futures.add(pool.submit(() -> {
                start.await();
                return f.ingest(tenant, externalId, f.sms(written));
            }));
        }
        start.countDown();
        List<PaymentIngestionResult> results = new ArrayList<>();
        for (Future<PaymentIngestionResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        List<PaymentIngestionResult> originals = results.stream().filter(r -> r.outcome() == Outcome.CREATED).toList();
        assertThat(originals).hasSize(1);
        assertThat(results).filteredOn(r -> r.outcome() == Outcome.CREATED_DUPLICATE_TX).hasSize(threads - 1)
                .extracting(PaymentIngestionResult::duplicateOf).containsOnly(originals.get(0).paymentId());
        assertThat(f.payments.count(tenant)).isEqualTo(threads);
    }

    @Test
    void sixteen_simultaneous_messages_with_one_transaction_id_make_one_original_and_fifteen_linked_duplicates() throws Exception {
        UUID tenant = f.tenant();
        String tx = txId();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentIngestionResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String externalId = id();
            futures.add(pool.submit(() -> {
                start.await();
                return f.ingest(tenant, externalId, f.sms(tx));
            }));
        }
        start.countDown();
        List<PaymentIngestionResult> results = new ArrayList<>();
        for (Future<PaymentIngestionResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        List<PaymentIngestionResult> originals = results.stream().filter(r -> r.outcome() == Outcome.CREATED).toList();
        assertThat(originals).hasSize(1);
        assertThat(results).filteredOn(r -> r.outcome() == Outcome.CREATED_DUPLICATE_TX).hasSize(threads - 1);
        assertThat(results).filteredOn(r -> r.outcome() == Outcome.CREATED_DUPLICATE_TX)
                .extracting(PaymentIngestionResult::duplicateOf).containsOnly(originals.get(0).paymentId());
        assertThat(f.payments.count(tenant)).isEqualTo(threads);
        assertThat(f.payments.findOriginal(tenant, "PROVIDER_A", tx)).contains(originals.get(0).paymentId());
        assertThat(f.audit.recent(tenant, 50)).hasSize(threads);
    }

    // ── Idempotency ────────────────────────────────────────────────────────

    @Test
    void the_same_external_id_keeps_exactly_one_payment() {
        UUID tenant = f.tenant();
        String externalId = id();
        String text = f.sms(txId());
        PaymentIngestionResult first = f.ingest(tenant, externalId, text);
        PaymentIngestionResult again = f.ingest(tenant, externalId, text);
        assertThat(first.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(again.outcome()).isEqualTo(Outcome.REPLAY);
        assertThat(again.paymentId()).isEqualTo(first.paymentId());
        assertThat(f.payments.count(tenant)).isEqualTo(1);
        assertThat(f.audit.recent(tenant, 10)).hasSize(1);
    }

    @Test
    void a_replay_with_different_text_keeps_the_first_payment_unchanged_and_says_so() {
        UUID tenant = f.tenant();
        String externalId = id();
        String text = f.sms(txId());
        PaymentIngestionResult first = f.ingest(tenant, externalId, text);
        PaymentIngestionResult other = f.ingest(tenant, externalId, f.sms(txId(), 999_999, "+999 000 000 1234"));
        assertThat(other.outcome()).isEqualTo(Outcome.REPLAY_DIFFERENT_TEXT);
        assertThat(other.paymentId()).isEqualTo(first.paymentId());
        PaymentRepository.Stored p = f.payments.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(p.rawText()).isEqualTo(text);
        assertThat(p.amountMinor()).isEqualTo(5000L);
        assertThat(f.payments.count(tenant)).isEqualTo(1);
    }

    @Test
    void sixteen_simultaneous_submissions_of_one_message_make_one_payment_and_one_audit_row() throws Exception {
        UUID tenant = f.tenant();
        String externalId = id();
        String text = f.sms(txId());
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentIngestionResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return f.ingest(tenant, externalId, text);
            }));
        }
        start.countDown();
        List<PaymentIngestionResult> results = new ArrayList<>();
        for (Future<PaymentIngestionResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();
        assertThat(results).filteredOn(r -> r.outcome() == Outcome.CREATED).hasSize(1);
        assertThat(results).filteredOn(r -> r.outcome() == Outcome.REPLAY).hasSize(threads - 1);
        assertThat(results).extracting(PaymentIngestionResult::paymentId).containsOnly(results.get(0).paymentId());
        assertThat(f.payments.count(tenant)).isEqualTo(1);
        assertThat(f.audit.recent(tenant, 50)).hasSize(1);
    }

    @Test
    void an_actor_from_another_tenant_is_refused_and_nothing_is_stored() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        UUID foreignUser = f.user(b);
        assertThatThrownBy(() -> f.service.ingest(a, foreignUser, id(), f.sms(txId()))).isInstanceOf(IllegalStateException.class);
        assertThat(f.payments.count(a)).isZero();
    }

    @Test
    void a_failure_while_storing_leaves_nothing_behind() {
        UUID tenant = f.tenant();
        // Make the audit insert fail for this tenant, the last step of the transaction.
        try (var c = PaymentFixture.DB.migrateDataSource().getConnection(); var s = c.createStatement()) {
            s.execute("ALTER TABLE audit_log ADD CONSTRAINT block_" + UUID.randomUUID().toString().replace("-", "")
                    + " CHECK (tenant_id <> '" + tenant + "'::uuid) NOT VALID");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThatThrownBy(() -> f.ingest(tenant, id(), f.sms(txId()))).isInstanceOf(IllegalStateException.class);
        assertThat(f.payments.count(tenant)).isZero();
    }

    // ── Tenants ────────────────────────────────────────────────────────────

    @Test
    void the_same_external_id_and_transaction_id_in_two_tenants_make_two_originals_and_each_sees_only_its_own() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        String externalId = id();
        String tx = txId();
        assertThat(f.ingest(a, externalId, f.sms(tx, 500, "+999 000 000 1234")).outcome()).isEqualTo(Outcome.CREATED);
        assertThat(f.ingest(b, externalId, f.sms(tx, 700, "+999 000 000 1234")).outcome()).isEqualTo(Outcome.CREATED);
        assertThat(f.payments.findByExternalId(a, externalId).orElseThrow().amountMinor()).isEqualTo(500L);
        assertThat(f.payments.findByExternalId(b, externalId).orElseThrow().amountMinor()).isEqualTo(700L);
        assertThat(f.payments.count(a)).isEqualTo(1);
        assertThat(f.payments.count(b)).isEqualTo(1);
    }

    @Test
    void an_unknown_tenant_fails_closed() {
        assertThatThrownBy(() -> f.ingest(UUID.randomUUID(), id(), f.sms(txId()))).isInstanceOf(IllegalStateException.class);
    }

    // ── The sender's phone ─────────────────────────────────────────────────

    @Test
    void the_senders_hash_is_the_hash_an_order_gives_the_same_customer_and_no_customer_is_created() {
        UUID tenant = f.tenant();
        CustomerRepository customers = new CustomerRepository(PaymentFixture.DB.appDataSource());
        OrderIngestionService orders = new OrderIngestionService(f.tenants, new CatalogRepository(PaymentFixture.DB.appDataSource()),
                new OrderRepository(PaymentFixture.DB.appDataSource()), f.limits, f.phoneKeys, new IngestionEvents(), dev.chatledger.matching.MatchTrigger.NONE);
        assertThat(orders.ingest(tenant, null, "order-" + UUID.randomUUID(), "Name: Ana\nPhone: 00 999-000-000-1234\n1 x rice @ 500").stored())
                .isTrue();
        assertThat(customers.count(tenant)).isEqualTo(1);

        String externalId = id();
        f.ingest(tenant, externalId, f.sms(txId()));
        String paymentHash = f.payments.findByExternalId(tenant, externalId).orElseThrow().senderPhoneHash();
        assertThat(customers.findByPhoneHash(tenant, paymentHash)).as("the customer made from the order is found by the payment's hash").isPresent();
        assertThat(customers.count(tenant)).as("a payment creates no customer").isEqualTo(1);
    }

    @Test
    void the_same_sender_in_two_tenants_has_two_unrelated_hashes() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        String idA = id();
        String idB = id();
        f.ingest(a, idA, f.sms(txId()));
        f.ingest(b, idB, f.sms(txId()));
        String hashA = f.payments.findByExternalId(a, idA).orElseThrow().senderPhoneHash();
        String hashB = f.payments.findByExternalId(b, idB).orElseThrow().senderPhoneHash();
        assertThat(hashA).isNotEqualTo(hashB).isEqualTo(f.phoneKeys.hash(a, ANA));
        assertThat(hashB).isEqualTo(f.phoneKeys.hash(b, ANA));
    }

    @Test
    void the_stored_row_holds_no_digit_of_the_senders_number_beyond_the_last_four() {
        UUID tenant = f.tenant();
        String externalId = id();
        f.ingest(tenant, externalId, f.sms(txId()));
        PaymentRepository.Stored p = f.payments.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(p.senderLast4()).isEqualTo("1234");
        assertThat(p.senderPhoneHash()).doesNotContain("999000000");
        assertThat(p.toString()).doesNotContain("Ana").doesNotContain(p.transactionId()).doesNotContain("+999").doesNotContain(p.senderPhoneHash());
    }

    // ── Rate limit ─────────────────────────────────────────────────────────

    @Test
    void ingestion_is_limited_per_tenant_and_the_budget_is_shared_with_orders() {
        PaymentFixture small = new PaymentFixture("60/1m/3");
        UUID a = small.tenant();
        UUID b = small.tenant();
        OrderIngestionService orders = new OrderIngestionService(small.tenants, new CatalogRepository(PaymentFixture.DB.appDataSource()),
                new OrderRepository(PaymentFixture.DB.appDataSource()), small.limits, small.phoneKeys, new IngestionEvents(), dev.chatledger.matching.MatchTrigger.NONE);
        assertThat(small.ingest(a, id(), small.sms(txId())).outcome()).isEqualTo(Outcome.CREATED);
        assertThat(orders.ingest(a, null, "order-" + UUID.randomUUID(), "1 x rice @ 5").stored()).isTrue();
        assertThat(small.ingest(a, id(), small.sms(txId())).outcome()).isEqualTo(Outcome.CREATED);
        PaymentIngestionResult limited = small.ingest(a, id(), small.sms(txId()));
        assertThat(limited.outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertThat(limited.retryAfterSeconds()).isBetween(1L, 2L);
        assertThat(small.payments.count(a)).as("a limited request stores nothing").isEqualTo(2);
        assertThat(orders.ingest(a, null, "order-" + UUID.randomUUID(), "1 x rice @ 5").outcome().name())
                .as("orders are limited by the same budget").isEqualTo("RATE_LIMITED");
        assertThat(small.ingest(b, id(), small.sms(txId())).outcome()).as("another tenant").isEqualTo(Outcome.CREATED);
        small.clock.advance(Duration.ofSeconds(2));
        assertThat(small.ingest(a, id(), small.sms(txId())).outcome()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void refused_input_does_not_spend_a_token() {
        PaymentFixture small = new PaymentFixture("60/1m/3");
        UUID tenant = small.tenant();
        for (int i = 0; i < 100; i++) {
            small.ingest(tenant, "bad id", "x");
        }
        assertThat(small.ingest(tenant, id(), small.sms(txId())).outcome()).isEqualTo(Outcome.CREATED);
    }

    // ── The hostile corpora, end to end ────────────────────────────────────

    @Test
    void every_hostile_message_is_stored_verbatim_as_unparsed_or_refused_and_nothing_reaches_the_log() {
        PaymentFixture big = new PaymentFixture("1000000/1m");
        UUID tenant = big.tenant();
        List<String> leakMarkers = List.of(HostilePayments.CANARY, HostileCorpus.CANARY, "forged log entry", "Ana K", "Ana ", "9990000001234",
                "999 000 000", "onerror", "HYPERLINK", "DROP TABLE", "<script>", "TX123456", "javascript:", "etc/passwd");
        List<Map.Entry<String, String>> all = new ArrayList<>(HostilePayments.all().entrySet());
        all.addAll(HostileCorpus.all().entrySet());
        try (LogCapture logs = new LogCapture()) {
            int stored = 0;
            int index = 0;
            for (Map.Entry<String, String> e : all) {
                String externalId = "corpus-" + index++;
                PaymentIngestionResult r;
                try {
                    r = big.ingest(tenant, externalId, e.getValue());
                } catch (RuntimeException ex) {
                    throw new AssertionError("the service threw for: " + e.getKey(), ex);
                }
                assertThat(r.outcome()).as(e.getKey()).isNotEqualTo(Outcome.RATE_LIMITED).isNotEqualTo(Outcome.REPLAY);
                if (r.stored()) {
                    stored++;
                    PaymentRepository.Stored p = big.payments.findByExternalId(tenant, externalId).orElseThrow();
                    assertThat(p.rawText()).as(e.getKey() + ": raw text is exactly what was sent").isEqualTo(e.getValue());
                    assertThat(p.status()).as(e.getKey() + ": no hostile text is read as a payment").isEqualTo("UNPARSED");
                    assertThat(p.currency()).as(e.getKey()).isEqualTo("XAF");
                    assertThat(p.amountMinor()).as(e.getKey()).isNull();
                } else {
                    assertThat(r.outcome()).as(e.getKey()).isEqualTo(Outcome.REJECTED_INPUT);
                }
            }
            assertThat(stored).as("most of the corpus is storable").isGreaterThan(40);
            assertThat(big.payments.count(tenant)).isEqualTo(stored);
            // A payment that is read, so the log has a "created" line carrying real values we can look for.
            String tx = txId();
            assertThat(big.ingest(tenant, id(), big.sms(tx)).outcome()).isEqualTo(Outcome.CREATED);
            String log = logs.text();
            assertThat(log).as("the log has the events, so this check is not vacuous").contains("payment ingested", "payment input rejected");
            for (String marker : leakMarkers) {
                assertThat(log).as("log must not contain: " + marker).doesNotContain(marker);
            }
            // (A bare "1234" is not checked: it can occur inside a random UUID in the log.)
            assertThat(log).doesNotContain(tx);
        }
    }

    @Test
    void results_and_stored_values_print_no_message_text() {
        UUID tenant = f.tenant();
        String externalId = id();
        String tx = txId();
        PaymentIngestionResult r = f.ingest(tenant, externalId, f.smsWithRef(tx, "A-1042"));
        PaymentRepository.Stored p = f.payments.findByExternalId(tenant, externalId).orElseThrow();
        for (String printed : new String[]{r.toString(), p.toString()}) {
            assertThat(printed).doesNotContain("Ana").doesNotContain(tx).doesNotContain("A-1042")
                    .doesNotContain("received");
        }
    }
}
