package dev.chatledger.board;

import dev.chatledger.board.BoardRepository.MatchView;
import dev.chatledger.matching.FloorCode;
import dev.chatledger.matching.MatchConfig;
import dev.chatledger.matching.MatchRepository;
import dev.chatledger.matching.MatchScorer;
import dev.chatledger.matching.Reason;
import dev.chatledger.matching.ReasonCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Turns a stored match into what a page shows: the reasons as codes (the page renders sentences from the codes), the conditions that kept
 * a suggestion from being automatic (found by scoring the pair again now, with the same pure scorer, so labelled "as of now"), and the amounts
 * a confirmation would apply. A reason code this version does not know is counted and shown as "another reason", never printed.
 */
@Component("explainer")
public class Explainer {

    /** One match, ready to show. {@code decider} is {@code you}, {@code other} or {@code system}. */
    public record Shown(MatchView match, List<ReasonCode> reasons, int otherReasons, List<FloorCode> missingNow, boolean rescored,
                        long wouldApplyMinor, long wouldExcessMinor, String decider) {
    }

    private final MatchRepository matches;
    private final MatchConfig config;

    public Explainer(MatchRepository matches, MatchConfig config) {
        this.matches = matches;
        this.config = config;
    }

    /**
     * @param viewer        the signed-in user (to say "you")
     * @param orderId       the order of the match
     * @param paymentId     the payment of the match
     * @param paymentMinor  the amount of the payment
     * @param balanceMinor  what the order still needs
     */
    public Shown explain(UUID tenantId, UUID viewer, MatchView match, UUID orderId, UUID paymentId, long paymentMinor, long balanceMinor) {
        return explain(tenantId, viewer, match, orderId, paymentId, paymentMinor, balanceMinor, false);
    }

    /**
     * @param pairFlagged true if the pair itself has a flag (open or resolved). The re-score cannot see it (facts carry only the payment's
     *                    and the order's own flags), so it is added to the missing conditions here.
     */
    public Shown explain(UUID tenantId, UUID viewer, MatchView match, UUID orderId, UUID paymentId, long paymentMinor, long balanceMinor,
                         boolean pairFlagged) {
        Reason.Parsed parsed = Reason.fromJson(match.reasonsJson());
        List<ReasonCode> codes = new ArrayList<>();
        for (Reason reason : parsed.reasons()) {
            codes.add(reason.code());
        }
        List<FloorCode> missing = List.of();
        boolean rescored = false;
        if ("SUGGESTED".equals(match.state())) {
            var order = matches.openOrder(tenantId, orderId);
            var payment = matches.paymentCandidate(tenantId, paymentId);
            if (order.isPresent() && payment.isPresent()) {
                var scored = MatchScorer.score(order.get(), payment.get().facts(), payment.get().recorder(), config);
                var absent = EnumSet.allOf(FloorCode.class);
                absent.removeAll(scored.floorsMet());
                if (pairFlagged) {
                    absent.add(FloorCode.NOT_FLAGGED);
                }
                missing = List.copyOf(absent);
                rescored = true;
            }
        }
        long apply = Math.max(0, Math.min(paymentMinor, balanceMinor));
        String decider = match.decidedBy() == null ? "system" : match.decidedBy().equals(viewer) ? "you" : "other";
        return new Shown(match, List.copyOf(codes), parsed.unknown() + (parsed.wellFormed() ? 0 : 1), missing, rescored, apply, Math.max(0, paymentMinor - apply),
                decider);
    }
}
