package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.SuspicionConfig;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static dev.chatledger.suspicion.Pairs.HASH_A;
import static dev.chatledger.suspicion.Pairs.HASH_B;
import static dev.chatledger.suspicion.Pairs.ORDERED;
import static dev.chatledger.suspicion.Pairs.order;
import static dev.chatledger.suspicion.Pairs.pair;
import static dev.chatledger.suspicion.Pairs.payment;
import static dev.chatledger.suspicion.rules.RuleTables.assertFlag;
import static dev.chatledger.suspicion.rules.RuleTables.assertPair;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pair rules R3, R5, R7, R8 and R11 with the example tables of the design stop, plus property tests for R3. Every finding is a pair
 * finding: it names the order, so it concerns that pair only.
 *
 * <p>Requirement: 5. Task 7.
 */
class PairRulesTest {
    private static final SuspicionConfig C = SuspicionConfig.defaults();

    // ── R3 ─────────────────────────────────────────────────────────────────
    @Test
    void r3_a_missing_or_swapped_digit_is_flagged_and_an_ordinary_part_payment_is_not() {
        AmountNearMissRule r = new AmountNearMissRule();
        long[][] yes = {{50000, 5000}, {12500, 1250}, {15000, 1500}, {4500, 5400}, {4500, 4050}, {4510, 4501}, {10500, 500}, {1000, 100}};
        long[][] no = {{5000, 3000}, {5000, 5000}, {5000, 500000}, {100, 10}, {999, 99}, {4500, 4500}, {4400, 4400}, {5000, 4999}, {12345, 1234567}};
        for (long[] row : yes) {
            var f = r.evaluate(pair(order(row[0], 0), payment(row[1])), C);
            assertFlag(f, true, FlagCode.AMOUNT_NEAR_MISS, row[0] + " paid " + row[1]);
            assertPair(f, row[0] + " paid " + row[1]);
        }
        for (long[] row : no) {
            assertFlag(r.evaluate(pair(order(row[0], 0), payment(row[1])), C), false, FlagCode.AMOUNT_NEAR_MISS, row[0] + " paid " + row[1]);
        }
    }

    @Test
    void r3_compares_with_the_balance_not_the_total() {
        AmountNearMissRule r = new AmountNearMissRule();
        // total 60000, 10000 already applied: the balance is 50000, so 5000 is a missing zero.
        assertFlag(r.evaluate(pair(order(60000, 10000), payment(5000)), C), true, FlagCode.AMOUNT_NEAR_MISS, "balance 50000");
        assertFlag(r.evaluate(pair(order(60000, 10000), payment(6000)), C), false, FlagCode.AMOUNT_NEAR_MISS, "6000 is a slip of the total, not the balance");
    }

    @Property(tries = 300)
    void r3_any_balance_with_one_digit_removed_is_flagged(@ForAll @LongRange(min = 1000, max = 1_000_000_000_000L) long balance,
                                                          @ForAll @IntRange(min = 0, max = 12) int position) {
        String digits = Long.toString(balance);
        int i = position % digits.length();
        String removed = digits.substring(0, i) + digits.substring(i + 1);
        long paid = Long.parseLong(removed);
        if (paid < 1 || paid == balance) {
            return;
        }
        assertThat(new AmountNearMissRule().evaluate(pair(order(balance, 0), payment(paid)), C)).as(balance + " paid " + paid).isPresent();
    }

    @Property(tries = 300)
    void r3_a_change_of_the_leading_digit_alone_is_never_flagged(@ForAll @LongRange(min = 1000, max = 999_999_999_999L) long balance,
                                                                 @ForAll @IntRange(min = 1, max = 9) int digit) {
        String digits = Long.toString(balance);
        String rest = digits.substring(1);
        if (digits.charAt(0) - '0' == digit || rest.chars().anyMatch(c -> c != '0')) {
            return;   // only round balances: then a substituted leading digit cannot also be a swap or a removal
        }
        long paid = Long.parseLong(digit + rest);
        assertThat(new AmountNearMissRule().evaluate(pair(order(balance, 0), payment(paid)), C)).as(balance + " paid " + paid).isEmpty();
    }

    // ── R5 ─────────────────────────────────────────────────────────────────
    @Test
    void r5_a_payment_well_before_the_order_is_flagged() {
        PaidBeforeOrderRule r = new PaidBeforeOrderRule();
        record Row(Duration offset, boolean flag) { }
        for (Row row : List.of(new Row(Duration.ofMinutes(-150), true), new Row(Duration.ofMinutes(-61), true), new Row(Duration.ofMinutes(-60), false),
                new Row(Duration.ofMinutes(-15), false), new Row(Duration.ofMinutes(5), false))) {
            var f = r.evaluate(pair(order(5000, null, null, null), payment(5000, null, null, null, ORDERED.plus(row.offset))), C);
            assertFlag(f, row.flag, FlagCode.PAID_BEFORE_ORDER, row.offset.toString());
            if (row.flag) {
                assertPair(f, row.offset.toString());
            }
        }
        assertFlag(r.evaluate(pair(order(5000, null, null, null), payment(5000, null, null, null, null)), C), false, FlagCode.PAID_BEFORE_ORDER, "no time");
    }

    // ── R7 ─────────────────────────────────────────────────────────────────
    @Test
    void r7_two_different_phones_are_flagged() {
        PhoneMismatchRule r = new PhoneMismatchRule();
        assertFlag(r.evaluate(pair(order(5000, null, HASH_A, "1234"), payment(5000, null, HASH_B, "5678", ORDERED)), C), true, FlagCode.PHONE_MISMATCH, "hash A, hash B");
        assertFlag(r.evaluate(pair(order(5000, null, HASH_A, "1234"), payment(5000, null, HASH_B, "1234", ORDERED)), C), true, FlagCode.PHONE_MISMATCH,
                "hashes differ although the last four agree");
        assertFlag(r.evaluate(pair(order(5000, null, HASH_A, "1234"), payment(5000, null, HASH_A, "1234", ORDERED)), C), false, FlagCode.PHONE_MISMATCH, "same hash");
        assertFlag(r.evaluate(pair(order(5000, null, null, "1234"), payment(5000, null, null, "5678", ORDERED)), C), true, FlagCode.PHONE_MISMATCH, "last fours differ");
        assertFlag(r.evaluate(pair(order(5000, null, HASH_A, "1234"), payment(5000, null, null, "5678", ORDERED)), C), true, FlagCode.PHONE_MISMATCH,
                "a hash on one side only: the last fours decide");
        assertFlag(r.evaluate(pair(order(5000, null, HASH_A, "1234"), payment(5000, null, null, "1234", ORDERED)), C), false, FlagCode.PHONE_MISMATCH, "last fours agree");
        assertFlag(r.evaluate(pair(order(5000, null, HASH_A, "1234"), payment(5000, null, null, null, ORDERED)), C), false, FlagCode.PHONE_MISMATCH, "no sender phone");
        assertFlag(r.evaluate(pair(order(5000, null, null, null), payment(5000, null, HASH_B, "5678", ORDERED)), C), false, FlagCode.PHONE_MISMATCH, "no order phone");
    }

    // ── R8 ─────────────────────────────────────────────────────────────────
    @Test
    void r8_a_sender_name_unlike_the_customer_is_flagged_unless_the_phone_is_the_same() {
        NameMismatchRule r = new NameMismatchRule();
        assertFlag(r.evaluate(pair(order(5000, "Ana Kamga", HASH_A, "1234"), payment(5000, "Cy Synthetic", HASH_B, "5678", ORDERED)), C), true,
                FlagCode.NAME_MISMATCH, "different names, different phones");
        assertFlag(r.evaluate(pair(order(5000, "Ana Kamga", null, null), payment(5000, "Cy Synthetic", null, null, ORDERED)), C), true,
                FlagCode.NAME_MISMATCH, "different names, no phones");
        assertFlag(r.evaluate(pair(order(5000, "Ana Kamga", null, null), payment(5000, "A. Kamga", null, null, ORDERED)), C), false, FlagCode.NAME_MISMATCH, "initial");
        assertFlag(r.evaluate(pair(order(5000, "Ana Kamga", null, null), payment(5000, "Kamga Ana", null, null, ORDERED)), C), false, FlagCode.NAME_MISMATCH, "full");
        assertFlag(r.evaluate(pair(order(5000, "Ana Kamga", HASH_A, "1234"), payment(5000, "Bob Synthetic", HASH_A, "1234", ORDERED)), C), false,
                FlagCode.NAME_MISMATCH, "equal phone hashes");
        assertFlag(r.evaluate(pair(order(5000, "Unknown customer", null, null), payment(5000, "Bob Synthetic", null, null, ORDERED)), C), false,
                FlagCode.NAME_MISMATCH, "neutral customer name");
        assertFlag(r.evaluate(pair(order(5000, "Ana Kamga", null, null), payment(5000, null, null, null, ORDERED)), C), false, FlagCode.NAME_MISMATCH, "no sender name");
        assertFlag(r.evaluate(pair(order(5000, null, null, null), payment(5000, "Bob Synthetic", null, null, ORDERED)), C), false, FlagCode.NAME_MISMATCH, "no customer name");
        assertFlag(r.evaluate(pair(order(5000, "Al", null, null), payment(5000, "AL", null, null, ORDERED)), C), false, FlagCode.NAME_MISMATCH, "the same short name");
    }

    // ── R11 ────────────────────────────────────────────────────────────────
    @Test
    void r11_an_overpayment_of_ten_percent_or_more_is_flagged() {
        OverpaymentRule r = new OverpaymentRule();
        long[][] yes = {{5000, 5500}, {5000, 9000}, {5000, 20000}, {1, 2}};
        long[][] no = {{5000, 5499}, {5000, 5400}, {5000, 5000}, {5000, 3000}, {5000, 1}};
        for (long[] row : yes) {
            var f = r.evaluate(pair(order(row[0], 0), payment(row[1])), C);
            assertFlag(f, true, FlagCode.OVERPAYMENT, row[0] + " paid " + row[1]);
            assertPair(f, row[0] + " paid " + row[1]);
        }
        for (long[] row : no) {
            assertFlag(r.evaluate(pair(order(row[0], 0), payment(row[1])), C), false, FlagCode.OVERPAYMENT, row[0] + " paid " + row[1]);
        }
        // The balance, not the total: 1000 left on a 5000 order, 1500 paid is 50% over.
        assertFlag(r.evaluate(pair(order(5000, 4000), payment(1500)), C), true, FlagCode.OVERPAYMENT, "balance 1000");
        // An order with nothing left to pay is not compared at all (every payment would be "over" it).
        assertFlag(r.evaluate(pair(order(5000, 5000), payment(100)), C), false, FlagCode.OVERPAYMENT, "balance 0");
        // Near the top of the range the integer arithmetic does not overflow.
        assertFlag(r.evaluate(pair(order(1_000_000_000_000L, 0), payment(1_000_000_000_000L)), C), false, FlagCode.OVERPAYMENT, "max, exact");
    }
}
