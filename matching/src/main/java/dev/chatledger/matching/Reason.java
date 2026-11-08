package dev.chatledger.matching;

import java.util.List;
import java.util.Objects;

/** One contribution to a score: a code and its points. */
public record Reason(ReasonCode code, int points) {

    public Reason {
        Objects.requireNonNull(code, "code");
        if (points < 0 || points > 100) {
            throw new IllegalArgumentException("points must be between 0 and 100");
        }
    }

    /**
     * The reasons as JSON for {@code match.reasons_json}: enum names and integers only, so no text from a message can be stored here
     * (the column also has a CHECK on its character set).
     */
    public static String toJson(List<Reason> reasons) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Reason r = reasons.get(i);
            sb.append("{\"code\":\"").append(r.code().name()).append("\",\"points\":").append(r.points()).append('}');
        }
        return sb.append(']').toString();
    }
}
