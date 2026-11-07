package dev.chatledger.ledger;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

import java.util.List;

import dev.chatledger.ledger.LedgerScenario.Op;

/**
 * jqwik generators for the ledger properties. Amounts deliberately include the
 * edge values 1 and numbers at or near Long.MAX_VALUE, where a missing overflow
 * check would show up.
 */
public final class LedgerGenerators {

    private static final long MAX = Long.MAX_VALUE;

    private LedgerGenerators() {
    }

    /** Positive amounts: edge values, small everyday values, and the full long range. */
    public static Arbitrary<Long> amounts() {
        return Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.of(1L, 2L, MAX, MAX - 1, MAX / 2, MAX / 2 + 1)),
                Tuple.of(4, Arbitraries.longs().between(1L, 10_000L)),
                Tuple.of(1, Arbitraries.longs().between(1L, MAX)));
    }

    /** Positive amounts that leave room for at least one more unit below Long.MAX_VALUE. */
    public static Arbitrary<Long> amountsBelowMax() {
        return amounts().map(a -> a == MAX ? MAX - 1 : a);
    }

    /** Two positive amounts whose sum still fits in a long, often exactly Long.MAX_VALUE. */
    public static Arbitrary<Tuple.Tuple2<Long, Long>> splitAmounts() {
        return amountsBelowMax().flatMap(a -> Arbitraries.frequencyOf(
                        Tuple.of(2, Arbitraries.just(MAX - a)),
                        Tuple.of(1, Arbitraries.just(1L)),
                        Tuple.of(3, Arbitraries.longs().between(1L, MAX - a)))
                .map(b -> Tuple.of(a, b)));
    }

    /** Two different positive amounts. */
    public static Arbitrary<Tuple.Tuple2<Long, Long>> differentAmounts() {
        return Combinators.combine(amounts(), amounts()).as(Tuple::of)
                .filter(t -> !t.get1().equals(t.get2()));
    }

    /** Sequences of 1 to {@code maxOps} operations over {@code accountCount} accounts. */
    public static Arbitrary<List<Op>> operations(int accountCount, int maxOps) {
        Arbitrary<Integer> account = Arbitraries.integers().between(0, accountCount - 1);

        Arbitrary<Op> transfer = Combinators.combine(account, account, amounts())
                .<Op>as(LedgerScenario.Transfer::new);
        Arbitrary<Op> split = Combinators.combine(account, account, account, splitAmounts())
                .<Op>as((d, ca, cb, ab) -> new LedgerScenario.Split(d, ca, cb, ab.get1(), ab.get2()));
        Arbitrary<Op> unbalanced = Combinators.combine(account, account, differentAmounts())
                .<Op>as((d, c, amts) -> new LedgerScenario.Unbalanced(d, c, amts.get1(), amts.get2()));
        Arbitrary<Op> reverse = Arbitraries.integers().between(0, 1_000)
                .<Op>map(LedgerScenario.Reverse::new);
        Arbitrary<Op> postAndReverse = Combinators.combine(account, account, amounts())
                .<Op>as(LedgerScenario.PostAndReverse::new);

        return Arbitraries.frequencyOf(
                        Tuple.of(4, transfer),
                        Tuple.of(3, split),
                        Tuple.of(1, unbalanced),
                        Tuple.of(3, reverse),
                        Tuple.of(1, postAndReverse))
                .list().ofMinSize(1).ofMaxSize(maxOps);
    }
}
