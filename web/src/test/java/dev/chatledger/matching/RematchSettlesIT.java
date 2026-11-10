package dev.chatledger.matching;

import dev.chatledger.matching.MatchingBooksPropertyIT.AddMirrorPayment;
import dev.chatledger.matching.MatchingBooksPropertyIT.AddOrder;
import dev.chatledger.matching.MatchingBooksPropertyIT.AddPayment;
import dev.chatledger.matching.MatchingBooksPropertyIT.Manual;
import dev.chatledger.matching.MatchingBooksPropertyIT.Unmatch;
import dev.chatledger.matching.MatchingBooksPropertyIT.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A repeated rematch settles even when a pair is planned as automatic but kept suggestion-only by a flag (task 7, D3).
 *
 * <p>The scenario is MatchingBooksPropertyIT's failure for seed 3454080274824834196, shrunk by hand to five steps:
 * <ol>
 *   <li>a payment of 1,000 without a transaction id (forwarded text) arrives;</li>
 *   <li>an order of 2,500 with its own reference arrives;</li>
 *   <li>the seller matches the 1,000 to the order by hand: 1,500 left to pay;</li>
 *   <li>a payment of 2,500 mirroring the order arrives. Against 1,500 left it overpays, so the pair is flagged {@code OVERPAYMENT}
 *       and suggested;</li>
 *   <li>the seller unmatches the 1,000. The order has 2,500 left again, so the 2,500 payment now fits exactly and is planned as an
 *       automatic match.</li>
 * </ol>
 * Every rematch then plans the pair as automatic; the automatic step, under the lock, finds the pair's open flag and keeps it a
 * suggestion, keeping the suggestion already stored. Before the fix it reported that as one new suggestion on every pass, so a rematch
 * never reported "nothing changed" although nothing did. A suggestion that was already there is not a change.
 */
class RematchSettlesIT {

    @Test
    void a_flagged_pair_already_suggested_is_not_counted_again_so_a_repeated_rematch_settles() {
        World world = new World();
        new AddPayment(1, 2, 3, 5).run(world);
        new AddOrder(1, 0, 2).run(world);
        new Manual(12, 17).run(world);
        new AddMirrorPayment(18, 2, 0).run(world);
        new Unmatch(27).run(world);

        // The state the scenario is about: one order with 2,500 left, the mirror payment suggested for it and flagged as an overpayment.
        List<String> suggestions = MatchingFixture.rows("SELECT order_id || ' ' || payment_id || ' ' || id FROM match WHERE tenant_id = ? AND state = 'SUGGESTED'", world.tenant);
        assertThat(suggestions).hasSize(1);
        String[] pair = suggestions.get(0).split(" ");
        UUID order = UUID.fromString(pair[0]);
        UUID payment = UUID.fromString(pair[1]);
        UUID suggestion = UUID.fromString(pair[2]);
        assertThat(MatchingFixture.count("SELECT count(*) FROM flag WHERE tenant_id = ? AND code = 'OVERPAYMENT' AND resolved_at IS NULL AND payment_id = '"
                + payment + "' AND order_id = '" + order + "'", world.tenant, null)).as("the pair's open flag").isEqualTo(1);

        String before = MatchInvariants.fingerprint(world.tenant).replaceAll("audit=\\d+", "");
        for (int pass = 0; pass < 3; pass++) {
            MatchingService.RematchResult r = world.live.matcher.rematch(world.tenant, world.seller);
            assertThat(r.examined()).as("pass " + pass).isEqualTo(2);
            assertThat(r.applied()).as("pass " + pass + ": nothing applied (the flag keeps the pair suggestion-only)").isZero();
            assertThat(r.suggested()).as("pass " + pass + ": the suggestion already stored is not counted again").isZero();
            assertThat(MatchInvariants.fingerprint(world.tenant).replaceAll("audit=\\d+", "")).as("pass " + pass + " changed nothing").isEqualTo(before);
            MatchInvariants.assertHold(world.tenant);
        }

        // D3 still holds, and the automatic step itself says it kept, not wrote, the suggestion.
        MatchingService.ApplyResult again = world.live.matcher.apply(world.tenant, order, payment, MatchingService.Mode.AUTO, null);
        assertThat(again.outcome()).isEqualTo(MatchingService.Outcome.DEGRADED_TO_SUGGESTION);
        assertThat(again.reason()).isEqualTo(MatchingService.Reason.FLAGGED);
        assertThat(again.matchId()).as("the suggestion already there").isEqualTo(suggestion);
        assertThat(again.suggestionWritten()).isFalse();
        assertThat(MatchingFixture.count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'AUTO'", world.tenant, null)).as("no automatic match").isZero();
        assertThat(world.live.matcher.onPaymentRecorded(world.tenant, payment).suggestions()).as("the payment's own run counts nothing either").isZero();
    }
}
