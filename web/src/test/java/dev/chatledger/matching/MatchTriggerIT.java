package dev.chatledger.matching;

import dev.chatledger.orders.IngestionResult;
import dev.chatledger.payments.PaymentIngestionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the ingestion services tell the matcher, and what they do if it fails: only after a record is stored and committed, only for a
 * record that can be matched, and never in a way that changes what ingestion returns.
 *
 * <p>Requirements: 3.1. Task 6.2 (decision D10).
 */
class MatchTriggerIT {

    /** Records every call, and checks from inside the call that the record is already committed and readable. */
    private static final class Recording implements MatchTrigger {
        final List<String> calls = new ArrayList<>();
        MatchingFixture fixture;
        UUID tenant;
        boolean fail;
        boolean visible = true;

        @Override
        public void paymentStored(UUID tenantId, UUID paymentId) {
            calls.add("payment");
            visible &= fixture.paymentRepository.count(tenantId) > 0;
            if (fail) {
                throw new IllegalStateException("the matcher failed");
            }
        }

        @Override
        public void orderStored(UUID tenantId, UUID orderId) {
            calls.add("order");
            visible &= fixture.orderRepository.count(tenantId) > 0;
            if (fail) {
                throw new IllegalStateException("the matcher failed");
            }
        }
    }

    private final Recording trigger = new Recording();
    private final MatchingFixture f = new MatchingFixture(trigger);
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;

    {
        trigger.fixture = f;
        trigger.tenant = tenant;
    }

    @Test
    void a_stored_payable_order_and_a_stored_payment_are_announced_after_the_commit() {
        f.orders.ingest(tenant, null, id(), MatchingFixture.orderText("A-1", "Ana Kamga", MatchingFixture.PHONE, 5000));
        f.payments.ingest(tenant, null, id(), f.sms(5000, "Ana Kamga", MatchingFixture.PHONE, "A-1"));
        f.payments.ingest(tenant, null, id(), f.forwarded(5000, "Ana Kamga", "1234"));
        assertThat(trigger.calls).containsExactly("order", "payment", "payment");
        assertThat(trigger.visible).as("the record could be read from inside the call").isTrue();
    }

    @Test
    void records_that_can_never_be_matched_are_not_announced() {
        w.run("INSERT INTO catalog_item(id, tenant_id, name_key, display_name, unit_minor) VALUES (?, ?, ?, 'Rice', 1500)", UUID.randomUUID(), tenant,
                dev.chatledger.extraction.TextKeys.key("rice"));
        f.orders.ingest(tenant, null, id(), "2 x rice\n1 x mystery");                        // needs review
        f.orders.ingest(tenant, null, id(), "Hello, two boxes of tomatoes please");          // unparsed
        f.payments.ingest(tenant, null, id(), "Hello, I paid you this morning");             // unparsed
        assertThat(trigger.calls).isEmpty();
    }

    @Test
    void replays_duplicates_and_refused_input_are_not_announced() {
        String externalId = id();
        String order = MatchingFixture.orderText("A-1", "Ana Kamga", MatchingFixture.PHONE, 5000);
        f.orders.ingest(tenant, null, externalId, order);
        f.orders.ingest(tenant, null, externalId, order);                                     // replay
        f.orders.ingest(tenant, null, "bad id", order);                                       // refused
        String sms = f.sms(5000, "Ana Kamga", MatchingFixture.PHONE, null);
        String paymentId = id();
        f.payments.ingest(tenant, null, paymentId, sms);
        f.payments.ingest(tenant, null, paymentId, sms);                                      // replay
        PaymentIngestionResult duplicate = f.payments.ingest(tenant, null, id(), sms);        // same transaction id: a duplicate
        assertThat(duplicate.outcome()).isEqualTo(PaymentIngestionResult.Outcome.CREATED_DUPLICATE_TX);
        assertThat(trigger.calls).containsExactly("order", "payment");
    }

    @Test
    void a_failing_matcher_changes_nothing_that_ingestion_returns_or_stored() {
        trigger.fail = true;
        IngestionResult order = f.orders.ingest(tenant, null, id(), MatchingFixture.orderText("A-1", "Ana Kamga", MatchingFixture.PHONE, 5000));
        PaymentIngestionResult payment = f.payments.ingest(tenant, null, id(), f.sms(5000, "Ana Kamga", MatchingFixture.PHONE, "A-1"));
        assertThat(order.outcome()).isEqualTo(IngestionResult.Outcome.CREATED);
        assertThat(payment.outcome()).isEqualTo(PaymentIngestionResult.Outcome.CREATED);
        assertThat(trigger.calls).containsExactly("order", "payment");
        assertThat(MatchingFixture.status("orders", order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.status("payment", payment.paymentId())).isEqualTo("RECEIVED");
        assertThat(f.orderRepository.count(tenant)).isEqualTo(1);
        assertThat(f.paymentRepository.count(tenant)).isEqualTo(1);
    }

    @Test
    void the_matchers_own_trigger_methods_swallow_a_failure_and_log_only_ids() {
        MatchingFixture failing = new MatchingFixture(MatchConfig.defaults(), (step, c) -> {
            throw new IllegalStateException("injected");
        }, false);
        UUID seller = MatchingFixture.user(tenant, "SELLER");
        MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        UUID order = quiet.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("A-1", "Ana Kamga", MatchingFixture.PHONE, 5000)).orderId();
        UUID payment = quiet.payments.ingest(tenant, seller, id(), quiet.sms(5000, "Ana Kamga", MatchingFixture.PHONE, "A-1")).paymentId();
        try (dev.chatledger.orders.LogCapture logs = new dev.chatledger.orders.LogCapture()) {
            failing.matcher.paymentStored(tenant, payment);    // would apply, and fails: must not throw
            failing.matcher.orderStored(tenant, order);
            String log = logs.text();
            assertThat(log).contains("matching failed", payment.toString(), order.toString());
            assertThat(log).doesNotContain("Ana").doesNotContain("injected").doesNotContain("Kamga").doesNotContain("999 000");
        }
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");
    }

    @Test
    void the_log_of_a_whole_run_carries_no_text_from_a_message() {
        UUID seller = MatchingFixture.user(tenant, "SELLER");
        MatchingFixture live = new MatchingFixture();
        try (dev.chatledger.orders.LogCapture logs = new dev.chatledger.orders.LogCapture()) {
            live.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("SECRET-REF", "Secret Sender", MatchingFixture.PHONE, 5000));
            live.payments.ingest(tenant, seller, id(), live.sms(5000, "Secret Sender", MatchingFixture.PHONE, "SECRET-REF"));
            String log = logs.text();
            assertThat(log).contains("match applied");
            assertThat(log).doesNotContain("SECRET").doesNotContain("Secret").doesNotContain("999 000").doesNotContain("rice");
        }
    }
}
