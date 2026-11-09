package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.SubjectType;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class RuleTables {
    private RuleTables() {
    }

    static void assertFlag(Optional<Finding> f, boolean expected, FlagCode code, String row) {
        assertThat(f.isPresent()).as(row).isEqualTo(expected);
        f.ifPresent(x -> assertThat(x.code()).as(row).isEqualTo(code));
    }

    static void assertPair(Optional<Finding> f, String row) {
        assertThat(f).as(row).isPresent();
        assertThat(f.get().subject()).isEqualTo(SubjectType.PAYMENT);
        assertThat(f.get().orderId()).as(row + ": a pair finding names its order").isNotNull();
    }
}
