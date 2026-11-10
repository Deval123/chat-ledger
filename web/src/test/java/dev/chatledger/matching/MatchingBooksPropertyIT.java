package dev.chatledger.matching;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;
import net.jqwik.api.Tuple;
import net.jqwik.api.stateful.Action;
import net.jqwik.api.stateful.ActionSequence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Random sequences of payments, orders and the seller's decisions (confirm, reject, match by hand, unmatch, set aside, take back,
 * rematch) against PostgreSQL, through the real ingestion services and the matcher, with the engine running at every ingestion.
 * After every step the books are read straight from the tables and must satisfy {@link MatchInvariants}: no payment assigned more
 * than its amount and none to two orders, no order assigned more than its total, the ledger balanced, receivables equal to the
 * accepted order totals minus the active matches, customer credit equal to the excess, and every status the function of the matches.
 * Further properties: repeating a seller's decision changes nothing; no automatic match is ever made against a pair the seller
 * rejected or reversed; and a rematch, repeated, reaches a point where it changes nothing.
 *
 * <p>Fewer tries than an in-memory property, because every step is a round trip to a real database. All names and numbers are
 * synthetic. Requirements: 3, 4, 10. Task 6.4.
 */
class MatchingBooksPropertyIT {

    private static final String[] NAMES = {"Ana Kamga", "Bea Nkomo", "Cy Obi"};
    private static final String[] PHONES = {"+999 000 000 1234", "+999 000 000 5678", "+999 000 000 7777"};
    private static final String SHARED_REF = "SH-1";
    private static final long[] TOTALS = {1000, 2500, 5000, 5000, 7000};
    private static final long[] AMOUNTS = {500, 1000, 2500, 5000, 7000, 12000};

    /** The model: one tenant, its users, and the pairs the seller has turned down so far. */
    static final class World {
        final MatchingFixture live = new MatchingFixture();
        final MatchWorld w = new MatchWorld();
        final UUID tenant = w.tenant;
        final UUID seller = MatchingFixture.user(tenant, "SELLER");
        final UUID helper = MatchingFixture.user(tenant, "HELPER");
        final Set<List<UUID>> declined = new HashSet<>();
        /** The orders placed so far, to write payments that mirror them. */
        final List<Placed> placed = new ArrayList<>();
        int counter;

        List<UUID> ids(String sql) {
            List<UUID> out = new ArrayList<>();
            for (String s : MatchingFixture.rows(sql, tenant)) {
                out.add(UUID.fromString(s));
            }
            return out;
        }

        /** The index-th row of the list, wrapping round; a random id (a refusal to be) when the list is empty. */
        static UUID pick(List<UUID> list, int index) {
            return list.isEmpty() ? UUID.randomUUID() : list.get(index % list.size());
        }

        UUID anyMatch(int i) {
            return pick(ids("SELECT id FROM match WHERE tenant_id = ? ORDER BY id"), i);
        }

        UUID suggestion(int i) {
            return pick(ids("SELECT id FROM match WHERE tenant_id = ? AND state = 'SUGGESTED' ORDER BY id"), i);
        }

        UUID activeMatch(int i) {
            return pick(ids("SELECT id FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED') ORDER BY id"), i);
        }

        UUID anyOrder(int i) {
            return pick(ids("SELECT id FROM orders WHERE tenant_id = ? ORDER BY id"), i);
        }

        UUID anyPayment(int i) {
            return pick(ids("SELECT id FROM payment WHERE tenant_id = ? ORDER BY id"), i);
        }

        List<UUID> pairOf(UUID matchId) {
            return live.repository.find(tenant, matchId).map(m -> List.of(m.orderId(), m.paymentId())).orElse(List.of());
        }

        /** A seller's decision, run twice: the second time must change nothing at all. */
        <T> T twice(Supplier<T> decision) {
            T first = decision.get();
            String after = MatchInvariants.fingerprint(tenant);
            decision.get();
            assertThat(MatchInvariants.fingerprint(tenant)).as("repeating the decision changed something").isEqualTo(after);
            return first;
        }
    }

    record Placed(String ref, int who, long total) {
    }

    // ── The steps ──────────────────────────────────────────────────────────

    /** {@code ref}: 0 no reference, 1 a reference shared with other orders, 2 to 4 a reference of its own. */
    record AddOrder(int total, int who, int ref) implements Action<World> {
        @Override
        public World run(World world) {
            String reference = ref % 5 == 0 ? null : ref % 5 == 1 ? SHARED_REF : "U-" + (world.counter++);
            long value = TOTALS[total % TOTALS.length];
            world.live.orders.ingest(world.tenant, world.seller, id(), MatchingFixture.orderText(reference, NAMES[who % 3], PHONES[who % 3], value));
            world.placed.add(new Placed(reference, who % 3, value));
            return world;
        }
    }

    /**
     * A payment written to mirror an order already placed (its reference, name, number and, mostly, its exact total), so that automatic
     * matches, ties and over- and under-payments really happen. {@code scale}: 0, 1 or 2 exact; 3 half; 4 double. {@code by} as in
     * {@link AddPayment}.
     */
    record AddMirrorPayment(int pick, int scale, int by) implements Action<World> {
        @Override
        public World run(World world) {
            if (world.placed.isEmpty()) {
                return world;
            }
            Placed order = world.placed.get(pick % world.placed.size());
            long value = switch (scale % 6) {
                case 4 -> Math.max(1, order.total() / 2);
                case 5 -> order.total() * 2;
                default -> order.total();
            };
            UUID actor = switch (by % 6) {
                case 3 -> world.helper;
                case 4 -> null;
                default -> world.seller;
            };
            String text = by % 6 == 5 ? world.live.forwarded(value, NAMES[order.who()], "1234")
                    : world.live.sms(value, NAMES[order.who()], PHONES[order.who()], order.ref());
            world.live.payments.ingest(world.tenant, actor, id(), text);
            return world;
        }
    }

    /** {@code by}: 0 to 2 the seller, 3 a helper, 4 nobody (a caller that passed null), 5 the seller with a forwarded text that has no transaction id. */
    record AddPayment(int amount, int who, int ref, int by) implements Action<World> {
        @Override
        public World run(World world) {
            long value = AMOUNTS[amount % AMOUNTS.length];
            UUID actor = switch (by % 6) {
                case 3 -> world.helper;
                case 4 -> null;
                default -> world.seller;
            };
            String text = by % 6 == 5 ? world.live.forwarded(value, NAMES[who % 3], "1234") : world.live.sms(value, NAMES[who % 3], PHONES[who % 3], ref % 5 == 0 ? null : ref % 5 == 1 ? SHARED_REF : "U-" + (ref % 7));
            world.live.payments.ingest(world.tenant, actor, id(), text);
            return world;
        }
    }

    record Confirm(int pick) implements Action<World> {
        @Override
        public World run(World world) {
            UUID match = world.suggestion(pick);
            world.twice(() -> world.live.matcher.confirm(world.tenant, match, world.seller));
            return world;
        }
    }

    record ConfirmAnything(int pick) implements Action<World> {
        @Override
        public World run(World world) {
            UUID match = world.anyMatch(pick);
            world.twice(() -> world.live.matcher.confirm(world.tenant, match, world.seller));
            return world;
        }
    }

    record Reject(int pick) implements Action<World> {
        @Override
        public World run(World world) {
            UUID match = world.suggestion(pick);
            List<UUID> pair = world.pairOf(match);
            MatchingService.Decision d = world.twice(() -> world.live.matcher.reject(world.tenant, match, world.seller));
            if (d.done()) {
                world.declined.add(pair);
            }
            return world;
        }
    }

    record Manual(int order, int payment) implements Action<World> {
        @Override
        public World run(World world) {
            UUID o = world.anyOrder(order);
            UUID p = world.anyPayment(payment);
            world.twice(() -> world.live.matcher.matchManually(world.tenant, o, p, world.seller));
            return world;
        }
    }

    record Unmatch(int pick) implements Action<World> {
        @Override
        public World run(World world) {
            UUID match = world.activeMatch(pick);
            List<UUID> pair = world.pairOf(match);
            MatchingService.Decision d = world.twice(() -> world.live.matcher.unmatch(world.tenant, match, world.seller));
            if (d.done()) {
                world.declined.add(pair);
            }
            return world;
        }
    }

    /** The seller undoes a match and the engine looks again at once (as it does when anything new arrives): the pair must stay apart. */
    record UnmatchThenRematch(int pick) implements Action<World> {
        @Override
        public World run(World world) {
            UUID match = world.activeMatch(pick);
            List<UUID> pair = world.pairOf(match);
            MatchingService.Decision d = world.live.matcher.unmatch(world.tenant, match, world.seller);
            if (d.done()) {
                world.declined.add(pair);
                assertThat(world.live.matcher.rematch(world.tenant, world.seller).refusal()).isNull();
                world.live.matcher.onPaymentRecorded(world.tenant, pair.get(1));
                world.live.matcher.onOrderRecorded(world.tenant, pair.get(0));
            }
            return world;
        }
    }

    record UnmatchAnything(int pick) implements Action<World> {
        @Override
        public World run(World world) {
            UUID match = world.anyMatch(pick);
            world.twice(() -> world.live.matcher.unmatch(world.tenant, match, world.seller));
            return world;
        }
    }

    record SetAside(int pick) implements Action<World> {
        @Override
        public World run(World world) {
            UUID payment = world.anyPayment(pick);
            // setting aside rejects the payment's open suggestions on the seller's behalf: those pairs are declined too
            List<UUID> suggested = world.ids("SELECT id FROM match WHERE tenant_id = ? AND state = 'SUGGESTED' ORDER BY id");
            List<List<UUID>> pairs = new ArrayList<>();
            for (UUID s : suggested) {
                List<UUID> pair = world.pairOf(s);
                if (pair.get(1).equals(payment)) {
                    pairs.add(pair);
                }
            }
            MatchingService.Decision d = world.twice(() -> world.live.matcher.markUnmatched(world.tenant, payment, world.seller));
            if (d.done()) {
                world.declined.addAll(pairs);
            }
            return world;
        }
    }

    record TakeBack(int pick) implements Action<World> {
        @Override
        public World run(World world) {
            UUID payment = world.anyPayment(pick);
            world.twice(() -> world.live.matcher.reopen(world.tenant, payment, world.seller));
            return world;
        }
    }

    record Rematch() implements Action<World> {
        @Override
        public World run(World world) {
            assertThat(world.live.matcher.rematch(world.tenant, world.seller).refusal()).isNull();
            return world;
        }
    }

    // ── The properties ─────────────────────────────────────────────────────

    @Provide
    Arbitrary<ActionSequence<World>> sequences() {
        Arbitrary<Integer> small = Arbitraries.integers().between(0, 40);
        Arbitrary<Action<World>> steps = Arbitraries.frequencyOf(
                Tuple.of(5, Arbitraries.integers().between(0, 4).flatMap(t -> Arbitraries.integers().between(0, 2)
                        .flatMap(who -> Arbitraries.integers().between(0, 4).map(ref -> (Action<World>) new AddOrder(t, who, ref))))),
                Tuple.of(7, small.flatMap(pick -> Arbitraries.integers().between(0, 5)
                        .flatMap(scale -> Arbitraries.integers().between(0, 5).map(by -> (Action<World>) new AddMirrorPayment(pick, scale, by))))),
                Tuple.of(3, Arbitraries.integers().between(0, 5).flatMap(a -> Arbitraries.integers().between(0, 2)
                        .flatMap(who -> Arbitraries.integers().between(0, 4)
                                .flatMap(ref -> Arbitraries.integers().between(0, 5).map(by -> (Action<World>) new AddPayment(a, who, ref, by)))))),
                Tuple.of(3, small.map(i -> (Action<World>) new Confirm(i))),
                Tuple.of(1, small.map(i -> (Action<World>) new ConfirmAnything(i))),
                Tuple.of(2, small.map(i -> (Action<World>) new Reject(i))),
                Tuple.of(3, small.flatMap(o -> small.map(p -> (Action<World>) new Manual(o, p)))),
                Tuple.of(3, small.map(i -> (Action<World>) new Unmatch(i))),
                Tuple.of(1, small.map(i -> (Action<World>) new UnmatchAnything(i))),
                Tuple.of(3, small.map(i -> (Action<World>) new UnmatchThenRematch(i))),
                Tuple.of(2, small.map(i -> (Action<World>) new SetAside(i))),
                Tuple.of(1, small.map(i -> (Action<World>) new TakeBack(i))),
                Tuple.of(1, Arbitraries.just((Action<World>) new Rematch())));
        return Arbitraries.sequences(steps).ofSize(20);
    }

    @Property(tries = 30)
    void whatever_the_sequence_the_books_stay_right_and_a_repeated_decision_changes_nothing(@ForAll("sequences") ActionSequence<World> sequence) {
        World world = new World();
        sequence.withInvariant("the books", w -> MatchInvariants.assertHold(w.tenant))
                .withInvariant("no automatic match against a pair the seller turned down", w -> {
                    for (List<UUID> pair : w.declined) {
                        assertThat(MatchingFixture.count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'AUTO' AND order_id = '" + pair.get(0)
                                + "' AND payment_id = '" + pair.get(1) + "'", w.tenant, null)).as("an automatic match for a declined pair").isZero();
                    }
                })
                .run(world);
        MatchInvariants.assertHold(world.tenant);
        // What the sequences really did, so that the property cannot pass by doing nothing. Each flow must happen at least once in the run;
        // a higher bar would fail on an unlucky draw, and a failure of this kind is a failure of the generators, not of the matcher.
        Statistics.label("an automatic match was made").collect(had(world, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'MATCH_AUTO'"))
                .coverage(c -> c.check(true).percentage(p -> p > 0.0));
        Statistics.label("a suggestion was confirmed").collect(had(world, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'MATCH_CONFIRMED'"))
                .coverage(c -> c.check(true).percentage(p -> p > 0.0));
        Statistics.label("a match was made by hand").collect(had(world, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'MATCH_MANUAL'"))
                .coverage(c -> c.check(true).percentage(p -> p > 0.0));
        Statistics.label("a suggestion was rejected").collect(had(world, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'MATCH_REJECTED'"))
                .coverage(c -> c.check(true).percentage(p -> p > 0.0));
        Statistics.label("a match was reversed").collect(had(world, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'MATCH_REVERSED'"))
                .coverage(c -> c.check(true).percentage(p -> p > 0.0));
        Statistics.label("a payment was set aside").collect(had(world, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'PAYMENT_UNMATCHED'"))
                .coverage(c -> c.check(true).percentage(p -> p > 0.0));
        Statistics.label("a payment was over what the order could take").collect(had(world, "SELECT count(*) FROM match WHERE tenant_id = ? AND excess_minor > 0"))
                .coverage(c -> c.check(true).percentage(p -> p > 0.0));
        Statistics.label("a match paid less than the order total").collect(had(world, "SELECT count(*) FROM match m JOIN orders o ON o.id = m.order_id "
                        + "WHERE m.tenant_id = ? AND m.amount_minor < o.total_minor"))
                .coverage(c -> c.check(true).percentage(p -> p > 0.0));
    }

    private static boolean had(World world, String sql) {
        return MatchingFixture.count(sql, world.tenant, null) > 0;
    }

    @Property(tries = 20)
    void a_rematch_repeated_reaches_a_point_where_it_changes_nothing(@ForAll("sequences") ActionSequence<World> sequence) {
        rematchSettles(sequence);
    }

    /**
     * The regression example for the non-convergent rematch (task 13 follow-up; RematchSettlesIT has the scenario shrunk by hand): this
     * seed's fifth sequence ends with a pair planned as automatic but kept suggestion-only by an open flag, and the rematch reported its
     * stored suggestion as new on every pass. Fixed seed, so the same sequences run every time.
     */
    @Property(tries = 5, seed = "3454080274824834196")
    void the_sequences_of_seed_3454080274824834196_settle(@ForAll("sequences") ActionSequence<World> sequence) {
        rematchSettles(sequence);
    }

    private static void rematchSettles(ActionSequence<World> sequence) {
        World world = new World();
        sequence.run(world);
        int free = (int) MatchingFixture.count("SELECT count(*) FROM payment WHERE tenant_id = ? AND status IN ('RECEIVED', 'UNVERIFIED')", world.tenant, null);
        boolean stable = false;
        for (int pass = 0; pass <= free + 1 && !stable; pass++) {
            MatchingService.RematchResult r = world.live.matcher.rematch(world.tenant, world.seller);
            stable = r.applied() == 0 && r.suggested() == 0;
            MatchInvariants.assertHold(world.tenant);
        }
        assertThat(stable).as("a rematch kept changing things for more passes than there are free payments").isTrue();
        String settled = MatchInvariants.fingerprint(world.tenant);
        world.live.matcher.rematch(world.tenant, world.seller);
        assertThat(MatchInvariants.fingerprint(world.tenant).replaceAll("audit=\\d+", "")).isEqualTo(settled.replaceAll("audit=\\d+", ""));
    }
}
