package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the match repository reads and stores as the application role: which orders and payments are candidates, the balance of an order,
 * suggestions and their uniqueness, and that nothing that comes out prints message text.
 *
 * <p>Requirements: 3.1, 3.3. Task 6.1.
 */
class MatchRepositoryIT {

    private final MatchRepository repository = new MatchRepository(MatchWorld.DB.appDataSource());
    private final MatchWorld w = new MatchWorld();
    private final Instant now = Instant.now();

    // ── Candidate orders ───────────────────────────────────────────────────

    @Test
    void only_open_orders_with_a_total_and_something_left_to_pay_are_candidates() {
        UUID pending = w.order(5000, "PENDING_PAYMENT", null, null);
        UUID partly = w.order(5000, "PARTIALLY_PAID", null, null);
        w.order(5000, "PAID", null, null);
        w.order(5000, "NEEDS_REVIEW", null, null);
        w.orderWithoutTotal("NEEDS_REVIEW");
        w.orderWithoutTotal("UNPARSED");
        w.order(5000, "CANCELLED", null, null);
        assertThat(repository.openOrders(w.tenant, "XAF", 100)).extracting(OrderFacts::id).containsExactlyInAnyOrder(pending, partly);
    }

    @Test
    void the_balance_counts_active_matches_only() {
        UUID order = w.order(5000, "PARTIALLY_PAID", null, null);
        UUID paid = w.payment(2000, "MATCHED");
        w.activeMatch(order, paid, "CONFIRMED", 2000, 0);
        // none of these applies anything
        w.suggested(order, w.payment(1000, "RECEIVED"));
        w.rejected(order, w.payment(1000, "RECEIVED"), true);
        OrderFacts facts = repository.openOrders(w.tenant, "XAF", 100).get(0);
        assertThat(facts.totalMinor()).isEqualTo(5000);
        assertThat(facts.appliedMinor()).isEqualTo(2000);
        assertThat(facts.balanceMinor()).isEqualTo(3000);
        assertThat(repository.appliedMinor(w.tenant, order)).isEqualTo(2000);
    }

    @Test
    void a_reversed_match_no_longer_counts() {
        UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
        w.reversedMatch(order, w.payment(5000, "RECEIVED"), 5000);
        assertThat(repository.openOrders(w.tenant, "XAF", 100).get(0).appliedMinor()).isZero();
        assertThat(repository.appliedMinor(w.tenant, order)).isZero();
    }

    @Test
    void orders_come_with_their_customers_fields_and_the_neutral_name_is_carried_as_it_is() {
        UUID customer = w.customer("Ana Kamga", MatchWorld.HASH, "1234");
        UUID neutral = w.customer("Unknown customer", "cd".repeat(32), "5678");
        w.order(5000, "PENDING_PAYMENT", "A-1", customer);
        w.order(4000, "PENDING_PAYMENT", null, neutral);
        w.order(3000, "PENDING_PAYMENT", null, null);
        List<OrderFacts> orders = repository.openOrders(w.tenant, "XAF", 100);
        OrderFacts named = orders.stream().filter(o -> o.totalMinor() == 5000).findFirst().orElseThrow();
        assertThat(named.customerName()).isEqualTo("Ana Kamga");
        assertThat(named.customerPhoneHash()).isEqualTo(MatchWorld.HASH);
        assertThat(named.customerLast4()).isEqualTo("1234");
        assertThat(named.orderRef()).isEqualTo("A-1");
        assertThat(named.currency()).isEqualTo("XAF");
        OrderFacts unknown = orders.stream().filter(o -> o.totalMinor() == 3000).findFirst().orElseThrow();
        assertThat(unknown.customerName()).isNull();
        assertThat(unknown.customerPhoneHash()).isNull();
        assertThat(unknown.customerLast4()).isNull();
        assertThat(unknown.orderRef()).isNull();
        assertThat(orders.stream().filter(o -> o.totalMinor() == 4000).findFirst().orElseThrow().customerName()).isEqualTo(NameSimilarity.NEUTRAL_NAME);
    }

    @Test
    void a_reference_shared_by_two_open_orders_is_marked_shared_whatever_its_case() {
        w.order(1000, "PENDING_PAYMENT", "A-1042", null);
        w.order(2000, "PARTIALLY_PAID", "a-1042", null);
        w.order(3000, "PENDING_PAYMENT", "B-7", null);
        w.order(4000, "PENDING_PAYMENT", null, null);
        w.order(5000, "PENDING_PAYMENT", null, null);
        // a closed order with the same code does not make the code ambiguous
        w.order(6000, "PAID", "B-7", null);
        List<OrderFacts> orders = repository.openOrders(w.tenant, "XAF", 100);
        assertThat(orders).filteredOn(o -> o.totalMinor() == 1000 || o.totalMinor() == 2000).allMatch(OrderFacts::referenceShared);
        assertThat(orders).filteredOn(o -> o.totalMinor() == 3000 || o.totalMinor() >= 4000).noneMatch(OrderFacts::referenceShared);
    }

    @Test
    void orders_come_most_recent_first_and_the_limit_is_honoured() {
        UUID oldest = w.order(1000, "PENDING_PAYMENT", null, null, "XAF", now.minus(Duration.ofDays(3)));
        UUID middle = w.order(2000, "PENDING_PAYMENT", null, null, "XAF", now.minus(Duration.ofDays(2)));
        UUID newest = w.order(3000, "PENDING_PAYMENT", null, null, "XAF", now.minus(Duration.ofDays(1)));
        assertThat(repository.openOrders(w.tenant, "XAF", 100)).extracting(OrderFacts::id).containsExactly(newest, middle, oldest);
        assertThat(repository.openOrders(w.tenant, "XAF", 2)).extracting(OrderFacts::id).containsExactly(newest, middle);
        assertThat(repository.openOrders(w.tenant, "XAF", 100).get(0).createdAt()).isCloseTo(now.minus(Duration.ofDays(1)), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void only_orders_in_the_asked_currency_are_candidates() {
        UUID xaf = w.order(5000, "PENDING_PAYMENT", null, null);
        w.order(5000, "PENDING_PAYMENT", null, null, "EUR", now);
        assertThat(repository.openOrders(w.tenant, "XAF", 100)).extracting(OrderFacts::id).containsExactly(xaf);
        assertThat(repository.openOrders(w.tenant, "EUR", 100)).hasSize(1);
    }

    // ── Candidate payments ─────────────────────────────────────────────────

    @Test
    void only_received_and_unverified_payments_are_candidates() {
        UUID received = w.payment(1000, "RECEIVED", true);
        UUID unverified = w.payment(1000, "UNVERIFIED", false);
        w.payment(1000, "MATCHED", true);
        w.payment(1000, "UNMATCHED", true);
        w.duplicateOf(received, 1000);
        w.unparsedPayment();
        List<PaymentFacts> payments = repository.openPayments(w.tenant, "XAF", 100);
        assertThat(payments).extracting(PaymentFacts::id).containsExactlyInAnyOrder(received, unverified);
        assertThat(payments.stream().filter(p -> p.id().equals(received)).findFirst().orElseThrow().hasTransactionId()).isTrue();
        assertThat(payments.stream().filter(p -> p.id().equals(unverified)).findFirst().orElseThrow().hasTransactionId()).isFalse();
    }

    @Test
    void payments_come_with_their_parsed_fields() {
        UUID id = w.payment(7500, "RECEIVED", true, "A-1042", "Ana K.", MatchWorld.HASH, "1234", "XAF", now);
        PaymentFacts p = repository.openPayments(w.tenant, "XAF", 100).get(0);
        assertThat(p.id()).isEqualTo(id);
        assertThat(p.amountMinor()).isEqualTo(7500);
        assertThat(p.currency()).isEqualTo("XAF");
        assertThat(p.reference()).isEqualTo("A-1042");
        assertThat(p.senderName()).isEqualTo("Ana K.");
        assertThat(p.senderPhoneHash()).isEqualTo(MatchWorld.HASH);
        assertThat(p.senderLast4()).isEqualTo("1234");
        assertThat(p.paidAt()).isNotNull();
        UUID bare = w.payment(100, "RECEIVED", true);
        PaymentFacts b = repository.openPayments(w.tenant, "XAF", 100).stream().filter(x -> x.id().equals(bare)).findFirst().orElseThrow();
        assertThat(b.reference()).isNull();
        assertThat(b.senderName()).isNull();
        assertThat(b.senderPhoneHash()).isNull();
        assertThat(b.senderLast4()).isNull();
    }

    @Test
    void payments_come_most_recent_first_by_currency_and_within_the_limit() {
        UUID oldest = w.payment(1, "RECEIVED", true, null, null, null, null, "XAF", now.minus(Duration.ofDays(3)));
        UUID newest = w.payment(2, "RECEIVED", true, null, null, null, null, "XAF", now.minus(Duration.ofDays(1)));
        w.payment(3, "RECEIVED", true, null, null, null, null, "EUR", now);
        assertThat(repository.openPayments(w.tenant, "XAF", 100)).extracting(PaymentFacts::id).containsExactly(newest, oldest);
        assertThat(repository.openPayments(w.tenant, "XAF", 1)).extracting(PaymentFacts::id).containsExactly(newest);
    }

    // ── Suggestions ────────────────────────────────────────────────────────

    @Test
    void a_suggestion_is_stored_without_amounts_and_the_same_pair_is_not_stored_twice() {
        UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
        UUID payment = w.payment(5000, "RECEIVED");
        String reasons = Reason.toJson(List.of(new Reason(ReasonCode.AMOUNT_EXACT, 50), new Reason(ReasonCode.TIME_IN_WINDOW, 10)));
        Optional<UUID> id = repository.insertSuggestion(w.tenant, order, payment, 60, reasons);
        assertThat(id).isPresent();
        MatchRepository.Stored stored = repository.find(w.tenant, id.get()).orElseThrow();
        assertThat(stored.state()).isEqualTo("SUGGESTED");
        assertThat(stored.amountMinor()).as("a suggestion applies nothing").isNull();
        assertThat(stored.excessMinor()).isZero();
        assertThat(stored.score()).isEqualTo(60);
        assertThat(stored.reasonsJson()).isEqualTo(reasons);
        assertThat(stored.ledgerTxnId()).isNull();
        assertThat(stored.decidedBy()).isNull();
        assertThat(stored.decidedAt()).isNull();
        assertThat(repository.insertSuggestion(w.tenant, order, payment, 70, "[]")).as("the pair already has an open match").isEmpty();
        assertThat(repository.count(w.tenant)).isEqualTo(1);
        assertThat(repository.forPayment(w.tenant, payment)).hasSize(1);
        assertThat(repository.forOrder(w.tenant, order)).hasSize(1);
    }

    @Test
    void a_suggestion_with_free_text_in_its_reasons_is_refused_by_the_database() {
        UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
        UUID payment = w.payment(5000, "RECEIVED");
        assertThatThrownBy(() -> repository.insertSuggestion(w.tenant, order, payment, 60, "[\"Ana K.\"]")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.insertSuggestion(w.tenant, order, payment, 101, "[]")).isInstanceOf(IllegalStateException.class);
        assertThat(repository.count(w.tenant)).isZero();
    }

    @Test
    void a_pair_the_seller_rejected_is_remembered_but_one_the_system_superseded_is_not() {
        UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
        UUID bySeller = w.payment(5000, "RECEIVED");
        UUID bySystem = w.payment(5000, "RECEIVED");
        UUID neither = w.payment(5000, "RECEIVED");
        w.rejected(order, bySeller, true);
        w.rejected(order, bySystem, false);
        assertThat(repository.wasRejected(w.tenant, order, bySeller)).isTrue();
        assertThat(repository.wasRejected(w.tenant, order, bySystem)).isFalse();
        assertThat(repository.wasRejected(w.tenant, order, neither)).isFalse();
        assertThat(repository.wasRejected(w.tenant, UUID.randomUUID(), bySeller)).isFalse();
        // after a rejection the same pair can be proposed again by hand or by a later run; the repository does not forbid it
        assertThat(repository.insertSuggestion(w.tenant, order, bySeller, 70, "[]")).isPresent();
    }

    @Test
    void an_active_match_is_read_with_its_amounts_and_who_decided() {
        UUID order = w.order(5000, "PAID", null, null);
        UUID payment = w.payment(8000, "MATCHED");
        UUID id = w.activeMatch(order, payment, "CONFIRMED", 5000, 3000);
        MatchRepository.Stored stored = repository.find(w.tenant, id).orElseThrow();
        assertThat(stored.state()).isEqualTo("CONFIRMED");
        assertThat(stored.amountMinor()).isEqualTo(5000L);
        assertThat(stored.excessMinor()).isEqualTo(3000);
        assertThat(stored.ledgerTxnId()).isNotNull();
        assertThat(stored.decidedBy()).isEqualTo(w.user);
        assertThat(stored.decidedAt()).isNotNull();
    }

    @Test
    void nothing_that_comes_out_prints_message_text() {
        UUID customer = w.customer("Secret Name", MatchWorld.HASH, "1234");
        UUID order = w.order(5000, "PENDING_PAYMENT", "SECRET-REF", customer);
        UUID payment = w.payment(5000, "RECEIVED", true, "SECRET-REF", "Secret Name", MatchWorld.HASH, "1234", "XAF", now);
        UUID id = repository.insertSuggestion(w.tenant, order, payment, 70, "[]").orElseThrow();
        List<String> printed = List.of(repository.find(w.tenant, id).orElseThrow().toString(), repository.openOrders(w.tenant, "XAF", 10).get(0).toString(),
                repository.openPayments(w.tenant, "XAF", 10).get(0).toString());
        for (String text : printed) {
            assertThat(text).doesNotContain("Secret").doesNotContain("SECRET").doesNotContain(MatchWorld.HASH);
        }
    }
}
