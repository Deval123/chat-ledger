package dev.chatledger.matching;

import dev.chatledger.payments.PaymentRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing the matcher or the payment store hands around prints a name, a number, a reference, a transaction id, a hash or any part of a
 * message: a value that reaches a log line, an exception text or a test failure shows its identity and its status, and nothing else.
 *
 * <p>Requirements: 1.4, 9.2. Tasks 5 and 6.
 */
class MatchingRedactionTest {

    private static final List<String> SECRETS = List.of("Secret Name", "SECRET-REF", "TXSECRET99", "secret-hash-value", "last4-secret", "raw secret text",
            "AMOUNT_EXACT");   // not a secret, but the stored reasons of a match are not printed either

    private static void assertRedacted(Object value, String... mustShow) {
        String printed = String.valueOf(value);
        for (String secret : SECRETS) {
            assertThat(printed).as(value.getClass().getSimpleName() + " must not print " + secret).doesNotContain(secret);
        }
        for (String shown : mustShow) {
            assertThat(printed).as(value.getClass().getSimpleName() + " shows its identity").contains(shown);
        }
    }

    @Test
    void the_rows_locked_for_an_apply_print_their_identity_and_status_only() {
        UUID id = UUID.randomUUID();
        assertRedacted(new MatchRepository.LockedPayment(id, "RECEIVED", 5000L, "XAF", true, "SECRET-REF", "Secret Name", "secret-hash-value", "last4-secret",
                Instant.now(), Recorder.SELLER), id.toString(), "RECEIVED");
        assertRedacted(new MatchRepository.LockedOrder(id, "PENDING_PAYMENT", 5000L, "XAF", Instant.now(), "SECRET-REF", "Secret Name",
                "secret-hash-value", "last4-secret"), id.toString(), "PENDING_PAYMENT");
    }

    @Test
    void a_match_and_a_candidate_print_their_identity_only() {
        UUID id = UUID.randomUUID();
        assertRedacted(new MatchRepository.NewActive(id, UUID.randomUUID(), UUID.randomUUID(), "AUTO", 5000, 0, 90, "[{\"code\":\"AMOUNT_EXACT\"}]", UUID.randomUUID(), null, null),
                id.toString(), "AUTO");
        assertRedacted(new MatchRepository.Stored(id, UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 5000L, 0, 90, "[{\"code\":\"AMOUNT_EXACT\"}]",
                UUID.randomUUID(), UUID.randomUUID(), Instant.now()), id.toString(), "CONFIRMED");
        PaymentFacts facts = new PaymentFacts(id, 5000, "XAF", true, "SECRET-REF", "Secret Name", "secret-hash-value", "last4-secret", Instant.now());
        assertRedacted(new MatchRepository.PaymentCandidate(facts, Recorder.HELPER), id.toString(), "HELPER");
        assertRedacted(facts, id.toString());
        assertRedacted(new OrderFacts(id, 5000, 0, "XAF", Instant.now(), "SECRET-REF", "Secret Name", "secret-hash-value", "last4-secret", false), id.toString());
    }

    @Test
    void a_stored_payment_prints_its_identity_and_status_only() {
        UUID id = UUID.randomUUID();
        assertRedacted(new PaymentRepository.Stored(id, "ext-1", "RECEIVED", "provider-a-received", "PROVIDER_A", "TXSECRET99", 5000L, "XAF", "Secret Name",
                "secret-hash-value", "last4-secret", "SECRET-REF", Instant.now(), null, "raw secret text"), id.toString(), "RECEIVED");
        assertRedacted(new PaymentRepository.NewPayment("ext-1", "RECEIVED", "provider-a-received", "PROVIDER_A", "TXSECRET99", 5000L, "XAF", "Secret Name",
                "secret-hash-value", "last4-secret", "SECRET-REF", Instant.now(), "raw secret text", null, null), "RECEIVED");
    }
}
