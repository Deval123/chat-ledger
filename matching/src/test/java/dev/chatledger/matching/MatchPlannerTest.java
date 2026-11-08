package dev.chatledger.matching;

import dev.chatledger.matching.MatchPlanner.Kind;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * From scores to a plan: automatic only when the best pair is eligible and clear of the others, otherwise suggestions, otherwise nothing.
 *
 * <p>Requirement: 3.2, 3.3. Task 6.1.
 */
class MatchPlannerTest {

    private static final MatchConfig CONFIG = MatchConfig.defaults();
    private static final UUID PAYMENT = UUID.randomUUID();

    private static ScoredPair pair(int score, boolean eligible) {
        return new ScoredPair(UUID.randomUUID(), PAYMENT, score, List.of(new Reason(ReasonCode.AMOUNT_EXACT, Math.min(score, 50))),
                eligible ? EnumSet.allOf(FloorCode.class) : EnumSet.noneOf(FloorCode.class), eligible);
    }

    @Test
    void a_lone_eligible_pair_is_matched_automatically() {
        ScoredPair best = pair(90, true);
        MatchPlanner.Plan plan = MatchPlanner.plan(List.of(best), CONFIG);
        assertThat(plan.kind()).isEqualTo(Kind.AUTO);
        assertThat(plan.auto()).isSameAs(best);
        assertThat(plan.suggestions()).isEmpty();
    }

    @Test
    void an_eligible_pair_must_beat_the_next_candidate_by_the_tie_margin() {
        ScoredPair best = pair(90, true);
        assertThat(MatchPlanner.plan(List.of(best, pair(80, false)), CONFIG).kind()).as("exactly the margin").isEqualTo(Kind.AUTO);
        MatchPlanner.Plan close = MatchPlanner.plan(List.of(best, pair(81, false)), CONFIG);
        assertThat(close.kind()).isEqualTo(Kind.SUGGEST);
        assertThat(close.tied()).isTrue();
        assertThat(close.suggestions()).hasSize(2);
        assertThat(close.auto()).isNull();
    }

    @Test
    void a_competitor_below_the_suggestion_threshold_still_counts_for_the_margin() {
        MatchConfig wide = new MatchConfig(85, 70, true, 200, 30);
        assertThat(MatchPlanner.plan(List.of(pair(90, true), pair(61, false)), wide).kind()).as("61 is under the suggestion threshold of 70").isEqualTo(Kind.SUGGEST);
        assertThat(MatchPlanner.plan(List.of(pair(90, true), pair(60, false)), wide).kind()).isEqualTo(Kind.AUTO);
    }

    @Test
    void two_equal_eligible_pairs_are_never_automatic() {
        MatchPlanner.Plan plan = MatchPlanner.plan(List.of(pair(90, true), pair(90, true)), CONFIG);
        assertThat(plan.kind()).isEqualTo(Kind.SUGGEST);
        assertThat(plan.tied()).isTrue();
    }

    @Test
    void a_high_score_that_is_not_eligible_is_only_suggested() {
        MatchPlanner.Plan plan = MatchPlanner.plan(List.of(pair(100, false)), CONFIG);
        assertThat(plan.kind()).isEqualTo(Kind.SUGGEST);
        assertThat(plan.tied()).isFalse();
        assertThat(plan.suggestions()).hasSize(1);
    }

    @Test
    void the_suggestion_threshold_is_inclusive() {
        assertThat(MatchPlanner.plan(List.of(pair(60, false)), CONFIG).kind()).isEqualTo(Kind.SUGGEST);
        assertThat(MatchPlanner.plan(List.of(pair(59, false)), CONFIG).kind()).isEqualTo(Kind.NONE);
    }

    @Test
    void at_most_three_suggestions_best_first() {
        List<ScoredPair> candidates = List.of(pair(70, false), pair(90, false), pair(65, false), pair(80, false), pair(61, false));
        MatchPlanner.Plan plan = MatchPlanner.plan(candidates, CONFIG);
        assertThat(plan.suggestions()).extracting(ScoredPair::score).containsExactly(90, 80, 70);
    }

    @Test
    void nothing_scoring_zero_is_ever_a_candidate() {
        assertThat(MatchPlanner.plan(List.of(), CONFIG).kind()).isEqualTo(Kind.NONE);
        assertThat(MatchPlanner.plan(List.of(pair(0, false)), CONFIG).kind()).isEqualTo(Kind.NONE);
        MatchConfig lowest = new MatchConfig(85, 40, true, 200, 10);
        assertThat(MatchPlanner.plan(List.of(pair(40, false), pair(39, false)), lowest).suggestions()).hasSize(1);
    }

    @Test
    void more_candidates_than_the_cap_are_refused() {
        MatchConfig small = new MatchConfig(85, 60, true, 10, 10);
        List<ScoredPair> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(pair(50, false));
        }
        assertThat(MatchPlanner.plan(ten, small).kind()).isEqualTo(Kind.NONE);
        ten.add(pair(50, false));
        assertThatThrownBy(() -> MatchPlanner.plan(ten, small)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ties_are_broken_the_same_way_every_time_by_order_then_by_payment() {
        UUID low = new UUID(0, 1);
        UUID high = new UUID(0, 2);
        ScoredPair a = new ScoredPair(low, low, 70, List.of(), EnumSet.noneOf(FloorCode.class), false);
        ScoredPair b = new ScoredPair(low, high, 70, List.of(), EnumSet.noneOf(FloorCode.class), false);
        ScoredPair c = new ScoredPair(high, low, 70, List.of(), EnumSet.noneOf(FloorCode.class), false);
        ScoredPair d = new ScoredPair(high, high, 70, List.of(), EnumSet.noneOf(FloorCode.class), false);
        List<List<ScoredPair>> orders = List.of(List.of(a, b, c, d), List.of(d, c, b, a), List.of(b, d, a, c), List.of(c, a, d, b));
        for (List<ScoredPair> candidates : orders) {
            assertThat(MatchPlanner.plan(candidates, CONFIG).suggestions()).containsExactly(a, b, c);
        }
    }

    @Property
    boolean an_automatic_plan_is_always_eligible_and_clear_of_every_other_candidate(@ForAll("candidates") List<ScoredPair> candidates) {
        MatchPlanner.Plan plan = MatchPlanner.plan(candidates, CONFIG);
        if (plan.kind() != Kind.AUTO) {
            return plan.auto() == null && plan.suggestions().size() <= MatchPlanner.MAX_SUGGESTIONS;
        }
        return plan.auto().autoEligible() && plan.auto().score() >= CONFIG.autoThreshold()
                && candidates.stream().filter(c -> c != plan.auto()).allMatch(c -> plan.auto().score() - c.score() >= CONFIG.tieMargin());
    }

    @Provide
    Arbitrary<List<ScoredPair>> candidates() {
        Arbitrary<ScoredPair> one = Arbitraries.integers().between(0, 100).flatMap(score ->
                Arbitraries.of(true, false).map(eligible -> pair(score, eligible && score >= CONFIG.autoThreshold())));
        return one.list().ofMaxSize(12);
    }
}
