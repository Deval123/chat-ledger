package dev.chatledger.matching;

import dev.chatledger.ledger.jdbc.JdbcLedgerRepository;
import dev.chatledger.matching.MatchingService.ActionStep;
import dev.chatledger.tenancy.TenantScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a seller's decision leaves behind when it fails half way: nothing. A failure is injected after each step of an unmatch, a
 * rejection, and of setting a payment aside and taking it back; after every one, the ledger, the matches, both statuses and the audit
 * trail are exactly as they were (one comparable fingerprint), and the same call without the failure then succeeds once. A posting that
 * was already reversed behind the match's back, and books that the commit-time triggers refuse, are tested the same way.
 *
 * <p>Requirements: 3.7, 4.2, 4.3. Task 6.3.
 */
class SellerActionsRollbackIT {

    private static MatchingService.ActionHook failAfter(ActionStep target) {
        return (step, connection) -> {
            if (step == target) {
                throw new IllegalStateException("injected failure after " + step);
            }
        };
    }

    /** A tenant with a seller and the pieces each action needs. */
    private static final class World {
        final MatchingFixture live = new MatchingFixture();
        final MatchWorld w = new MatchWorld();
        final UUID tenant = w.tenant;
        final UUID seller = MatchingFixture.user(tenant, "SELLER");
        final UUID helper = MatchingFixture.user(tenant, "HELPER");

        UUID order = live.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("A-1042", "Ana Kamga", PHONE, 5000)).orderId();

        /** An automatic match of 5,000 on the order. */
        UUID autoMatch() {
            UUID payment = live.payments.ingest(tenant, seller, id(), live.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
            return live.repository.forPayment(tenant, payment).get(0).id();
        }

        /** A helper's payment for the order, so that the engine only suggested it. */
        UUID suggestion() {
            UUID payment = live.payments.ingest(tenant, helper, id(), live.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
            return live.repository.forPayment(tenant, payment).get(0).id();
        }

        UUID paymentWithSuggestion() {
            return live.repository.find(tenant, suggestion()).orElseThrow().paymentId();
        }

        UUID asidePayment() {
            UUID payment = paymentWithSuggestion();
            assertThat(live.matcher.markUnmatched(tenant, payment, seller).done()).isTrue();
            return payment;
        }
    }

    @Test
    void a_failure_after_any_step_of_an_unmatch_leaves_nothing_and_the_retry_reverses_once() {
        for (ActionStep step : EnumSet.of(ActionStep.PAYMENT_LOCKED, ActionStep.LOCKED, ActionStep.REVERSAL_POSTED, ActionStep.MATCH_REVERSED, ActionStep.ORDER_STATUS,
                ActionStep.PAYMENT_STATUS, ActionStep.AUDIT)) {
            World world = new World();
            UUID match = world.autoMatch();
            String before = MatchInvariants.fingerprint(world.tenant);
            MatchingFixture failing = new MatchingFixture(failAfter(step));
            assertThatThrownBy(() -> failing.matcher.unmatch(world.tenant, match, world.seller))
                    .as("after " + step).hasMessageContaining("injected failure after " + step);
            assertThat(MatchInvariants.fingerprint(world.tenant)).as("nothing is left after " + step).isEqualTo(before);
            MatchInvariants.assertHold(world.tenant);

            MatchingFixture working = new MatchingFixture(MatchingService.ActionHook.NONE);
            assertThat(working.matcher.unmatch(world.tenant, match, world.seller).done()).as("the retry after " + step).isTrue();
            assertThat(MatchingFixture.ledger(world.tenant)).as("reversed once after " + step).containsExactly("ORDER_ACCEPTED 1100 D5000",
                    "ORDER_ACCEPTED 4000 C5000", "PAYMENT_MATCHED 1000 D5000", "PAYMENT_MATCHED 1100 C5000", "REVERSAL 1000 C5000", "REVERSAL 1100 D5000");
            MatchInvariants.assertHold(world.tenant);
        }
    }

    @Test
    void a_failure_after_any_step_of_a_rejection_leaves_nothing_and_the_retry_rejects_once() {
        for (ActionStep step : EnumSet.of(ActionStep.LOCKED, ActionStep.MATCH_REJECTED, ActionStep.AUDIT)) {
            World world = new World();
            UUID suggestion = world.suggestion();
            String before = MatchInvariants.fingerprint(world.tenant);
            MatchingFixture failing = new MatchingFixture(failAfter(step));
            assertThatThrownBy(() -> failing.matcher.reject(world.tenant, suggestion, world.seller))
                    .as("after " + step).hasMessageContaining("injected failure after " + step);
            assertThat(MatchInvariants.fingerprint(world.tenant)).as("nothing is left after " + step).isEqualTo(before);

            MatchingFixture working = new MatchingFixture(MatchingService.ActionHook.NONE);
            assertThat(working.matcher.reject(world.tenant, suggestion, world.seller).done()).as("the retry after " + step).isTrue();
            assertThat(working.repository.find(world.tenant, suggestion).orElseThrow().state()).isEqualTo("REJECTED");
        }
    }

    @Test
    void a_failure_after_any_step_of_setting_a_payment_aside_leaves_nothing_and_the_retry_does_it_once() {
        for (ActionStep step : EnumSet.of(ActionStep.LOCKED, ActionStep.PAYMENT_STATUS, ActionStep.SUGGESTIONS_REJECTED, ActionStep.AUDIT)) {
            World world = new World();
            UUID payment = world.paymentWithSuggestion();
            String before = MatchInvariants.fingerprint(world.tenant);
            MatchingFixture failing = new MatchingFixture(failAfter(step));
            assertThatThrownBy(() -> failing.matcher.markUnmatched(world.tenant, payment, world.seller))
                    .as("after " + step).hasMessageContaining("injected failure after " + step);
            assertThat(MatchInvariants.fingerprint(world.tenant)).as("nothing is left after " + step).isEqualTo(before);
            assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");

            MatchingFixture working = new MatchingFixture(MatchingService.ActionHook.NONE);
            assertThat(working.matcher.markUnmatched(world.tenant, payment, world.seller).done()).as("the retry after " + step).isTrue();
            assertThat(MatchingFixture.status("payment", payment)).isEqualTo("UNMATCHED");
        }
    }

    @Test
    void a_failure_after_any_step_of_taking_a_payment_back_leaves_nothing_and_the_retry_does_it_once() {
        for (ActionStep step : EnumSet.of(ActionStep.LOCKED, ActionStep.PAYMENT_STATUS, ActionStep.AUDIT)) {
            World world = new World();
            UUID payment = world.asidePayment();
            String before = MatchInvariants.fingerprint(world.tenant);
            MatchingFixture failing = new MatchingFixture(failAfter(step));
            assertThatThrownBy(() -> failing.matcher.reopen(world.tenant, payment, world.seller))
                    .as("after " + step).hasMessageContaining("injected failure after " + step);
            assertThat(MatchInvariants.fingerprint(world.tenant)).as("nothing is left after " + step).isEqualTo(before);
            assertThat(MatchingFixture.status("payment", payment)).isEqualTo("UNMATCHED");

            MatchingFixture working = new MatchingFixture(MatchingService.ActionHook.NONE);
            assertThat(working.matcher.reopen(world.tenant, payment, world.seller).done()).as("the retry after " + step).isTrue();
            assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");
        }
    }

    @Test
    void a_posting_that_was_reversed_behind_the_matchs_back_stops_the_unmatch_and_leaves_nothing_more() throws Exception {
        World world = new World();
        UUID match = world.autoMatch();
        UUID txn = world.live.repository.find(world.tenant, match).orElseThrow().ledgerTxnId();
        TenantScope scope = new TenantScope(MatchWorld.DB.appDataSource());
        JdbcLedgerRepository ledger = new JdbcLedgerRepository(scope);
        scope.call(world.tenant, c -> ledger.reverseWithin(c, world.tenant, txn, UUID.randomUUID(), Instant.now()));
        String before = MatchInvariants.fingerprint(world.tenant);
        MatchingFixture working = new MatchingFixture(MatchingService.ActionHook.NONE);

        assertThatThrownBy(() -> working.matcher.unmatch(world.tenant, match, world.seller)).isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(dev.chatledger.ledger.AlreadyReversedException.class);

        assertThat(MatchInvariants.fingerprint(world.tenant)).isEqualTo(before);
        assertThat(working.repository.find(world.tenant, match).orElseThrow().state()).isEqualTo("AUTO");
    }

    @Test
    void if_the_books_would_be_inconsistent_the_commit_of_an_unmatch_is_refused_and_everything_rolls_back() {
        record Case(String name, ActionStep after, String sql) {
        }
        for (Case c : new Case[]{
                new Case("order status put back to paid", ActionStep.ORDER_STATUS, "UPDATE orders SET status = 'PAID' WHERE id = ?"),
                new Case("payment status put back to matched", ActionStep.PAYMENT_STATUS, "UPDATE payment SET status = 'MATCHED' WHERE id = ?"),
                new Case("match put back to automatic", ActionStep.AUDIT, "UPDATE match SET state = 'AUTO' WHERE id = ?")}) {
            World world = new World();
            UUID match = world.autoMatch();
            MatchRepository.Stored stored = world.live.repository.find(world.tenant, match).orElseThrow();
            UUID target = c.sql().contains("orders") ? stored.orderId() : c.sql().contains("payment") ? stored.paymentId() : match;
            String before = MatchInvariants.fingerprint(world.tenant);
            MatchingFixture sabotaged = new MatchingFixture((step, connection) -> {
                if (step == c.after()) {
                    MatchWorld.run(connection, c.sql(), target);
                }
            });
            assertThatThrownBy(() -> sabotaged.matcher.unmatch(world.tenant, match, world.seller)).as(c.name()).isInstanceOf(RuntimeException.class);
            assertThat(MatchInvariants.fingerprint(world.tenant)).as(c.name()).isEqualTo(before);
            MatchInvariants.assertHold(world.tenant);
        }
    }
}
