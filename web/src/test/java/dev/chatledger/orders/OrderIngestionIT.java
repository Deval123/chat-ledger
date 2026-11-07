package dev.chatledger.orders;

import dev.chatledger.extraction.FailureReason;
import dev.chatledger.extraction.HostileCorpus;
import dev.chatledger.extraction.Phone;
import dev.chatledger.orders.IngestionResult.Outcome;
import dev.chatledger.security.AuditLogRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.time.Duration;

import static dev.chatledger.orders.IngestionFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whole-service tests against PostgreSQL as the application role: what is stored, idempotency under concurrency,
 * tenant separation, the catalog, customers and phones, rate limits, and the logs. All names and numbers are synthetic.
 *
 * <p>Requirements: 1.1 to 1.5, 8, 9. Tasks 4.1 to 4.3.
 */
class OrderIngestionIT {

    private static final String EN = "Order: A-1042\nName: Ana K.\nPhone: +999 000 000 1234\n2 x tomato box @ 1500\n"
            + "1 x rice 5kg = 3000\nTotal: 6000 XAF\nDeliver: gate near the market";

    private final IngestionFixture f = new IngestionFixture();

    // ── What is stored ─────────────────────────────────────────────────────

    @Test
    void an_english_message_becomes_a_pending_order_with_its_customer_lines_and_audit_row() {
        UUID tenant = f.tenant();
        String externalId = id();
        UUID actor = f.user(tenant);
        IngestionResult r = f.service.ingest(tenant, actor, externalId, EN);
        assertThat(r.outcome()).isEqualTo(Outcome.CREATED);

        OrderRepository.Stored o = f.orders.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(o.id()).isEqualTo(r.orderId());
        assertThat(o.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(o.totalMinor()).isEqualTo(6000L);
        assertThat(o.currency()).isEqualTo("XAF");
        assertThat(o.orderRef()).isEqualTo("A-1042");
        assertThat(o.deliveryNote()).isEqualTo("gate near the market");
        assertThat(o.rawText()).as("kept exactly as received").isEqualTo(EN);
        assertThat(f.orders.lines(tenant, o.id())).containsExactlyInAnyOrder(
                new OrderRepository.StoredLine("tomato box", 2, 1500L), new OrderRepository.StoredLine("rice 5kg", 1, 3000L));

        CustomerRepository.Row customer = f.customers.find(tenant, o.customerId()).orElseThrow();
        assertThat(customer.displayName()).isEqualTo("Ana K.");
        assertThat(customer.phoneLast4()).isEqualTo("1234");
        String hash = f.phoneKeys.hash(tenant, Phone.of("+999 000 000 1234").orElseThrow());
        assertThat(f.customers.findByPhoneHash(tenant, hash)).isPresent();
        assertThat(hash).matches("[0-9a-f]{64}").doesNotContain("9990000001234");

        List<AuditLogRepository.Entry> audit = f.audit.recent(tenant, 10);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).action()).isEqualTo("ORDER_INGESTED");
        assertThat(audit.get(0).subject()).isEqualTo(o.id().toString());
        assertThat(audit.get(0).actor()).isEqualTo(actor);
    }

    @Test
    void a_french_message_with_fcfa_is_stored_in_the_tenants_xaf() {
        UUID tenant = f.tenant();
        String externalId = id();
        IngestionResult r = f.ingest(tenant, externalId, HostileCorpus.FrenchSamples.LABELLED);
        assertThat(r.outcome()).isEqualTo(Outcome.CREATED);
        OrderRepository.Stored o = f.orders.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(o.totalMinor()).isEqualTo(6000L);
        assertThat(o.currency()).isEqualTo("XAF");
        assertThat(o.orderRef()).isEqualTo("B-77");
        assertThat(f.customers.find(tenant, o.customerId()).orElseThrow().displayName()).isEqualTo("A\u00EFcha N.");
        assertThat(f.orders.lines(tenant, o.id())).extracting(OrderRepository.StoredLine::label)
                .containsExactlyInAnyOrder("bo\u00EEte de tomates", "riz 5kg");
    }

    @Test
    void a_one_line_message_works() {
        UUID tenant = f.tenant();
        IngestionResult r = f.ingest(tenant, id(), "2 x tomato box, 1 x rice 5kg @ 3000, total 6000 XAF, from Ana K.");
        assertThat(r.outcome()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void a_total_computed_from_the_catalog_is_stored_though_the_message_never_writes_it() {
        UUID tenant = f.tenant();
        f.seedCatalog(tenant, "Rice", 1500);
        f.seedCatalog(tenant, "Bo\u00EEte de tomates", 500);
        String text = "2 x rice\n1 x boite de tomates";
        String externalId = id();
        assertThat(f.ingest(tenant, externalId, text).outcome()).isEqualTo(Outcome.CREATED);
        OrderRepository.Stored o = f.orders.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(text).doesNotContain("3500");
        assertThat(o.totalMinor()).isEqualTo(3500L);
        assertThat(o.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(f.orders.lines(tenant, o.id())).extracting(OrderRepository.StoredLine::unitMinor).containsExactlyInAnyOrder(1500L, 500L);
    }

    @Test
    void an_unknown_price_needs_review_with_no_total_and_the_tenants_currency() {
        UUID tenant = f.tenant();
        f.seedCatalog(tenant, "rice", 1500);
        String externalId = id();
        IngestionResult r = f.ingest(tenant, externalId, "2 x rice\n1 x mystery");
        assertThat(r.outcome()).isEqualTo(Outcome.CREATED_NEEDS_REVIEW);
        assertThat(r.reason()).isEqualTo(FailureReason.PRICE_UNKNOWN);
        OrderRepository.Stored o = f.orders.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(o.status()).isEqualTo("NEEDS_REVIEW");
        assertThat(o.totalMinor()).isNull();
        assertThat(o.currency()).isEqualTo("XAF");
        assertThat(f.orders.lines(tenant, o.id())).extracting(OrderRepository.StoredLine::unitMinor).containsExactlyInAnyOrder(1500L, null);
    }

    @Test
    void a_message_that_cannot_be_parsed_is_kept_whole_as_unparsed() {
        UUID tenant = f.tenant();
        String text = "Hello! Could you send two boxes of tomatoes tomorrow please? Thanks, Ana";
        String externalId = id();
        IngestionResult r = f.ingest(tenant, externalId, text);
        assertThat(r.outcome()).isEqualTo(Outcome.CREATED_UNPARSED);
        assertThat(r.reason()).isEqualTo(FailureReason.UNRECOGNIZED_LINE);
        OrderRepository.Stored o = f.orders.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(o.status()).isEqualTo("UNPARSED");
        assertThat(o.rawText()).isEqualTo(text);
        assertThat(o.totalMinor()).isNull();
        assertThat(o.currency()).isEqualTo("XAF");
        assertThat(o.customerId()).isNull();
        assertThat(f.orders.lines(tenant, o.id())).isEmpty();
        assertThat(f.customers.count(tenant)).isZero();
    }

    @Test
    void a_message_in_another_currency_is_kept_unparsed_not_converted() {
        UUID tenant = f.tenant();
        assertThat(f.ingest(tenant, id(), "1 x rice @ 5 EUR").outcome()).isEqualTo(Outcome.CREATED_UNPARSED);
    }

    @Test
    void a_tenant_with_decimals_stores_minor_units() {
        UUID tenant = f.tenant("EUR", 2);
        String externalId = id();
        assertThat(f.ingest(tenant, externalId, "2 x tea @ 12.50\nTotal: 25.00 EUR").outcome()).isEqualTo(Outcome.CREATED);
        OrderRepository.Stored o = f.orders.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(o.totalMinor()).isEqualTo(2500L);
        assertThat(o.currency()).isEqualTo("EUR");
        assertThat(f.ingest(tenant, id(), "2 x tea @ 1,500").outcome()).as("ambiguous").isEqualTo(Outcome.CREATED_UNPARSED);
    }

    @Test
    void the_stated_total_wins_over_the_computed_one_and_the_order_needs_review() {
        UUID tenant = f.tenant();
        f.seedCatalog(tenant, "rice", 1500);
        String externalId = id();
        assertThat(f.ingest(tenant, externalId, "2 x rice\nTotal: 3500").outcome()).isEqualTo(Outcome.CREATED_NEEDS_REVIEW);
        OrderRepository.Stored o = f.orders.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(o.totalMinor()).as("the stated number is kept").isEqualTo(3500L);
        assertThat(o.status()).isEqualTo("NEEDS_REVIEW");
    }

    // ── Input is checked again here ────────────────────────────────────────

    @Test
    void invalid_input_is_refused_and_nothing_is_stored() {
        UUID tenant = f.tenant();
        Map<String, String[]> bad = Map.of(
                "nul", new String[]{id(), "1 x rice\u0000 @ 5"},
                "control", new String[]{id(), "1 x rice\u0007"},
                "lone surrogate", new String[]{id(), "1 x rice\uD800"},
                "too large", new String[]{id(), "1 x " + "a".repeat(4000)},
                "blank", new String[]{id(), "  \n "},
                "bad id", new String[]{"has space", "1 x rice"},
                "id with sql", new String[]{"x'; DROP TABLE orders;--", "1 x rice"},
                "long id", new String[]{"a".repeat(129), "1 x rice"});
        bad.forEach((name, input) -> {
            IngestionResult r = f.ingest(tenant, input[0], input[1]);
            assertThat(r.outcome()).as(name).isEqualTo(Outcome.REJECTED_INPUT);
            assertThat(r.rejectReason()).as(name).isNotNull();
            assertThat(r.orderId()).as(name).isNull();
        });
        assertThat(f.ingest(tenant, null, "1 x rice").outcome()).isEqualTo(Outcome.REJECTED_INPUT);
        assertThat(f.ingest(tenant, id(), null).outcome()).isEqualTo(Outcome.REJECTED_INPUT);
        assertThat(f.orders.count(tenant)).isZero();
        assertThat(f.customers.count(tenant)).isZero();
        assertThat(f.audit.recent(tenant, 10)).isEmpty();
    }

    // ── Idempotency ────────────────────────────────────────────────────────

    @Test
    void the_same_external_id_keeps_exactly_one_order() {
        UUID tenant = f.tenant();
        String externalId = id();
        IngestionResult first = f.ingest(tenant, externalId, EN);
        IngestionResult again = f.ingest(tenant, externalId, EN);
        assertThat(first.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(again.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(again.orderId()).isEqualTo(first.orderId());
        assertThat(f.orders.count(tenant)).isEqualTo(1);
        assertThat(f.customers.count(tenant)).isEqualTo(1);
        assertThat(f.audit.recent(tenant, 10)).hasSize(1);
    }

    @Test
    void a_replay_with_different_text_keeps_the_first_order_unchanged_and_says_so() {
        UUID tenant = f.tenant();
        String externalId = id();
        IngestionResult first = f.ingest(tenant, externalId, "1 x rice @ 500");
        IngestionResult other = f.ingest(tenant, externalId, "9 x gold @ 999999");
        assertThat(other.outcome()).isEqualTo(Outcome.DUPLICATE_DIFFERENT_TEXT);
        assertThat(other.orderId()).isEqualTo(first.orderId());
        OrderRepository.Stored o = f.orders.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(o.rawText()).isEqualTo("1 x rice @ 500");
        assertThat(o.totalMinor()).isEqualTo(500L);
        assertThat(f.orders.count(tenant)).isEqualTo(1);
    }

    @Test
    void sixteen_simultaneous_submissions_of_one_message_make_one_order_one_customer_one_audit_row() throws Exception {
        UUID tenant = f.tenant();
        String externalId = id();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestionResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<IngestionResult> task = () -> {
                start.await();
                return f.ingest(tenant, externalId, EN);
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        List<IngestionResult> results = new ArrayList<>();
        for (Future<IngestionResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(results).filteredOn(r -> r.outcome() == Outcome.CREATED).hasSize(1);
        assertThat(results).filteredOn(r -> r.outcome() == Outcome.DUPLICATE).hasSize(threads - 1);
        assertThat(results).extracting(IngestionResult::orderId).containsOnly(results.get(0).orderId());
        assertThat(f.orders.count(tenant)).isEqualTo(1);
        assertThat(f.customers.count(tenant)).as("a losing request leaves no customer behind").isEqualTo(1);
        assertThat(f.audit.recent(tenant, 50)).hasSize(1);
    }

    @Test
    void sixteen_simultaneous_submissions_with_a_name_only_customer_leave_no_orphans() throws Exception {
        UUID tenant = f.tenant();
        String externalId = id();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestionResult>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return f.ingest(tenant, externalId, "Name: Ana\n1 x rice @ 500");
            }));
        }
        start.countDown();
        for (Future<IngestionResult> future : futures) {
            future.get();
        }
        pool.shutdown();
        assertThat(f.orders.count(tenant)).isEqualTo(1);
        assertThat(f.customers.count(tenant)).as("the rolled-back requests created no customer rows").isEqualTo(1);
    }

    @Test
    void an_actor_from_another_tenant_is_refused_and_nothing_is_stored() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        UUID foreignUser = f.user(b);
        assertThatThrownBy(() -> f.service.ingest(a, foreignUser, id(), EN)).isInstanceOf(IllegalStateException.class);
        assertThat(f.orders.count(a)).isZero();
        assertThat(f.customers.count(a)).isZero();
    }

    @Test
    void a_failure_while_storing_leaves_nothing_behind() {
        UUID tenant = f.tenant();
        // Make the audit insert fail for this tenant, the last step of the transaction.
        try (var c = IngestionFixture.DB.migrateDataSource().getConnection(); var s = c.createStatement()) {
            s.execute("ALTER TABLE audit_log ADD CONSTRAINT block_" + UUID.randomUUID().toString().replace("-", "")
                    + " CHECK (tenant_id <> '" + tenant + "'::uuid) NOT VALID");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThatThrownBy(() -> f.ingest(tenant, id(), EN)).isInstanceOf(IllegalStateException.class);
        assertThat(f.orders.count(tenant)).isZero();
        assertThat(f.customers.count(tenant)).as("the customer was rolled back with the order").isZero();
    }

    // ── Tenants ────────────────────────────────────────────────────────────

    @Test
    void the_same_external_id_in_two_tenants_makes_two_orders_and_each_sees_only_its_own() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        String externalId = id();
        assertThat(f.ingest(a, externalId, "1 x rice @ 500").outcome()).isEqualTo(Outcome.CREATED);
        assertThat(f.ingest(b, externalId, "1 x beans @ 700").outcome()).isEqualTo(Outcome.CREATED);
        assertThat(f.orders.findByExternalId(a, externalId).orElseThrow().totalMinor()).isEqualTo(500L);
        assertThat(f.orders.findByExternalId(b, externalId).orElseThrow().totalMinor()).isEqualTo(700L);
        assertThat(f.orders.count(a)).isEqualTo(1);
        assertThat(f.orders.count(b)).isEqualTo(1);
    }

    @Test
    void one_tenants_catalog_is_never_used_for_another() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        f.seedCatalog(b, "rice", 1500);
        assertThat(f.ingest(a, id(), "1 x rice").outcome()).isEqualTo(Outcome.CREATED_NEEDS_REVIEW);
        assertThat(f.ingest(b, id(), "1 x rice").outcome()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void an_unknown_tenant_fails_closed() {
        assertThatThrownBy(() -> f.ingest(UUID.randomUUID(), id(), "1 x rice @ 5")).isInstanceOf(IllegalStateException.class);
    }

    // ── Catalog lookup is equality, not a pattern ──────────────────────────

    @Test
    void wildcards_and_quotes_in_an_item_name_do_not_match_catalog_items() {
        UUID tenant = f.tenant();
        f.seedCatalog(tenant, "rice", 1500);
        f.seedCatalog(tenant, "100% cotton", 2000);
        for (String name : new String[]{"r%", "ri_e", "ric", "rice'", "rice' OR 'x'", "ricee", "%ice", "r_ce"}) {
            IngestionResult r = f.ingest(tenant, id(), "1 x " + name);
            assertThat(r.outcome()).as(name).isEqualTo(Outcome.CREATED_NEEDS_REVIEW);
        }
        assertThat(f.ingest(tenant, id(), "1 x 100% cotton").outcome()).as("exact name, wildcard character and all").isEqualTo(Outcome.CREATED);
        assertThat(f.ingest(tenant, id(), "1 x RICE").outcome()).as("case-insensitive equality").isEqualTo(Outcome.CREATED);
        assertThat(f.ingest(tenant, id(), "1 x  r\u00EDce ").outcome()).as("accent-insensitive equality").isEqualTo(Outcome.CREATED);
    }

    // ── Customers and phones ───────────────────────────────────────────────

    @Test
    void one_phone_written_several_ways_is_one_customer_in_a_tenant() {
        UUID tenant = f.tenant();
        for (String spelling : new String[]{"+999 000 000 1234", "00 999-000-000-1234", "(+999) 000.000.1234", "9990000001234"}) {
            f.ingest(tenant, id(), "Phone: " + spelling + "\n1 x rice @ 5");
        }
        assertThat(f.customers.count(tenant)).isEqualTo(1);
        assertThat(f.orders.count(tenant)).isEqualTo(4);
    }

    @Test
    void the_limit_the_same_number_with_and_without_a_country_code_is_two_customers() {
        UUID tenant = f.tenant();
        f.ingest(tenant, id(), "Phone: +999 612 345 678\n1 x rice @ 5");
        f.ingest(tenant, id(), "Phone: 612 345 678\n1 x rice @ 5");
        assertThat(f.customers.count(tenant)).as("named limit: there is no country logic").isEqualTo(2);
    }

    @Test
    void the_same_phone_in_two_tenants_has_two_unrelated_hashes_and_each_tenant_sees_only_its_own() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        f.ingest(a, id(), "Phone: +999 000 000 1234\n1 x rice @ 5");
        Phone phone = Phone.of("+999 000 000 1234").orElseThrow();
        String hashA = f.phoneKeys.hash(a, phone);
        String hashB = f.phoneKeys.hash(b, phone);
        assertThat(hashA).isNotEqualTo(hashB);
        assertThat(f.customers.findByPhoneHash(a, hashA)).isPresent();
        assertThat(f.customers.findByPhoneHash(b, hashA)).isEmpty();
        assertThat(f.customers.findByPhoneHash(a, hashB)).isEmpty();
    }

    @Test
    void a_second_order_from_a_known_phone_keeps_the_first_name() {
        UUID tenant = f.tenant();
        f.ingest(tenant, id(), "Name: Ana\nPhone: +999 000 000 1234\n1 x rice @ 5");
        f.ingest(tenant, id(), "Name: Someone Else\nPhone: +999 000 000 1234\n1 x rice @ 5");
        Phone phone = Phone.of("+999 000 000 1234").orElseThrow();
        assertThat(f.customers.findByPhoneHash(tenant, f.phoneKeys.hash(tenant, phone)).orElseThrow().displayName()).isEqualTo("Ana");
        assertThat(f.customers.count(tenant)).isEqualTo(1);
    }

    @Test
    void customers_without_a_phone_are_not_merged_and_an_order_without_either_has_no_customer() {
        UUID tenant = f.tenant();
        f.ingest(tenant, id(), "Name: Ana\n1 x rice @ 5");
        f.ingest(tenant, id(), "Name: Ana\n1 x rice @ 5");
        assertThat(f.customers.count(tenant)).as("named limit: no safe key without a phone").isEqualTo(2);
        String externalId = id();
        f.ingest(tenant, externalId, "1 x rice @ 5");
        assertThat(f.orders.findByExternalId(tenant, externalId).orElseThrow().customerId()).isNull();
    }

    @Test
    void a_phone_without_a_name_gets_a_neutral_display_name() {
        UUID tenant = f.tenant();
        String externalId = id();
        f.ingest(tenant, externalId, "Phone: +999 000 000 9876\n1 x rice @ 5");
        UUID customer = f.orders.findByExternalId(tenant, externalId).orElseThrow().customerId();
        CustomerRepository.Row row = f.customers.find(tenant, customer).orElseThrow();
        assertThat(row.displayName()).isEqualTo("Unknown customer");
        assertThat(row.phoneLast4()).isEqualTo("9876");
    }

    // ── Rate limit ─────────────────────────────────────────────────────────

    @Test
    void ingestion_is_limited_per_tenant_and_tokens_return_over_time() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        for (int i = 1; i <= 30; i++) {
            assertThat(f.ingest(a, id(), "1 x rice @ 5").outcome()).as("burst " + i).isEqualTo(Outcome.CREATED);
        }
        IngestionResult limited = f.ingest(a, id(), "1 x rice @ 5");
        assertThat(limited.outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertThat(limited.retryAfterSeconds()).isBetween(1L, 2L);
        assertThat(f.orders.count(a)).as("a limited request stores nothing").isEqualTo(30);
        assertThat(f.ingest(b, id(), "1 x rice @ 5").outcome()).as("another tenant").isEqualTo(Outcome.CREATED);
        f.clock.advance(Duration.ofSeconds(2));
        assertThat(f.ingest(a, id(), "1 x rice @ 5").outcome()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void refused_input_does_not_spend_a_token() {
        UUID tenant = f.tenant();
        for (int i = 0; i < 100; i++) {
            f.ingest(tenant, "bad id", "1 x rice");
        }
        assertThat(f.ingest(tenant, id(), "1 x rice @ 5").outcome()).isEqualTo(Outcome.CREATED);
    }

    // ── The hostile corpus, end to end ─────────────────────────────────────

    @Test
    void every_hostile_message_is_stored_as_data_or_refused_and_nothing_reaches_the_log() {
        IngestionFixture big = new IngestionFixture("1000000/1m");
        UUID tenant = big.tenant();
        List<String> leakMarkers = List.of(HostileCorpus.CANARY, "forged log entry", "Ana K", "Ana ", "9990000001234", "999 000 000",
                "onerror", "HYPERLINK", "DROP TABLE", "<script>", "tomato", "A\u00EFcha", "javascript:", "etc/passwd");
        try (LogCapture logs = new LogCapture()) {
            int stored = 0;
            int index = 0;
            for (Map.Entry<String, String> e : HostileCorpus.all().entrySet()) {
                String externalId = "corpus-" + index++;
                IngestionResult r;
                try {
                    r = big.ingest(tenant, externalId, e.getValue());
                } catch (RuntimeException ex) {
                    throw new AssertionError("the service threw for: " + e.getKey(), ex);
                }
                assertThat(r.outcome()).as(e.getKey()).isNotIn(Outcome.RATE_LIMITED, Outcome.DUPLICATE, Outcome.DUPLICATE_DIFFERENT_TEXT);
                if (r.stored()) {
                    stored++;
                    OrderRepository.Stored o = big.orders.findByExternalId(tenant, externalId).orElseThrow();
                    assertThat(o.rawText()).as(e.getKey() + ": raw text is exactly what was sent").isEqualTo(e.getValue());
                    assertThat(o.currency()).as(e.getKey()).isEqualTo("XAF");
                    if (o.status().equals("UNPARSED")) {
                        assertThat(o.totalMinor()).as(e.getKey()).isNull();
                    }
                    for (OrderRepository.StoredLine line : big.orders.lines(tenant, o.id())) {
                        assertThat(line.label().length()).as(e.getKey()).isBetween(1, 100);
                    }
                } else {
                    assertThat(r.outcome()).as(e.getKey()).isEqualTo(Outcome.REJECTED_INPUT);
                }
            }
            assertThat(stored).as("most of the corpus is storable").isGreaterThan(40);
            assertThat(big.orders.count(tenant)).isEqualTo(stored);
            String log = logs.text();
            assertThat(log).as("the log has the ingestion events, so this check is not vacuous").contains("order ingested", "order input rejected");
            for (String marker : leakMarkers) {
                assertThat(log).as("log must not contain: " + marker).doesNotContain(marker);
            }
        }
    }

    @Test
    void hostile_text_is_stored_verbatim_as_data() {
        UUID tenant = f.tenant();
        String externalId = id();
        f.ingest(tenant, externalId, HostileCorpus.get("markup as a name"));
        OrderRepository.Stored o = f.orders.findByExternalId(tenant, externalId).orElseThrow();
        assertThat(f.customers.find(tenant, o.customerId()).orElseThrow().displayName())
                .isEqualTo("<img src=x onerror=alert('" + HostileCorpus.CANARY + "')>");
        String sqlId = id();
        f.ingest(tenant, sqlId, HostileCorpus.get("sql in an item"));
        assertThat(f.orders.count(tenant)).as("the tables are still there and the SQL was only text").isEqualTo(2);
    }

    @Test
    void results_and_stored_values_print_no_message_text() {
        UUID tenant = f.tenant();
        String externalId = id();
        IngestionResult r = f.ingest(tenant, externalId, EN);
        OrderRepository.Stored o = f.orders.findByExternalId(tenant, externalId).orElseThrow();
        for (String printed : new String[]{r.toString(), o.toString(), f.orders.lines(tenant, o.id()).get(0).toString(),
                f.customers.find(tenant, o.customerId()).orElseThrow().toString()}) {
            assertThat(printed).doesNotContain("Ana").doesNotContain("tomato").doesNotContain("1234").doesNotContain("gate")
                    .doesNotContain("A-1042").doesNotContain("Order:");
        }
    }
}
