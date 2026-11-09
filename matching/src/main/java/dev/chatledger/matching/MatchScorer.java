package dev.chatledger.matching;

import dev.chatledger.extraction.ReferenceKeys;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Scores one order against one payment, from 0 to 100, with the reasons as codes. Pure: no database, no clock, no text output.
 *
 * <h2>Signals</h2>
 * <ul>
 *   <li><b>Amount</b>: the payment equals the order balance, 50; below the balance (partial) or above it up to twice (over-payment),
 *       20; otherwise 0. The balance is the total minus the active matches, supplied by the caller from locked rows.</li>
 *   <li><b>Reference</b>: the payment's reference equals the order's code under {@link ReferenceKeys}, 25. If another open order has
 *       the same code the reference proves nothing and scores 0.</li>
 *   <li><b>Identity</b>, at most 15: equal phone hash 15; otherwise name agreement ({@link NameSimilarity}) 12 for a full agreement or
 *       8 for an initial, plus 3 if the last four digits are equal (only when the name already agrees).</li>
 *   <li><b>Time</b>: the claimed payment time is between 10 minutes before and 7 days after the order was created, 10.</li>
 * </ul>
 *
 * <h2>What text cannot do</h2>
 * Every input here is a parsed field. Anyone who can write the SMS can write its amount, reference, name and time, so no score is
 * proof. What stops a forged message is that an <em>automatic</em> match needs all of the floors ({@link FloorCode}, plus the tie
 * margin in {@link MatchPlanner}) and not only a high score, that a match moves no money, and that the seller can reverse it.
 */
public final class MatchScorer {

    public static final int AMOUNT_EXACT_POINTS = 50;
    public static final int AMOUNT_PARTIAL_POINTS = 20;
    public static final int AMOUNT_OVER_POINTS = 20;
    public static final int REFERENCE_POINTS = 25;
    public static final int IDENTITY_PHONE_POINTS = 15;
    public static final int IDENTITY_NAME_FULL_POINTS = 12;
    public static final int IDENTITY_NAME_INITIAL_POINTS = 8;
    public static final int IDENTITY_LAST4_POINTS = 3;
    public static final int TIME_POINTS = 10;
    /** An over-payment is scored only up to this multiple of the balance. */
    public static final long OVER_PAYMENT_MULTIPLE = 2;
    public static final Duration TIME_BEFORE_ORDER = Duration.ofMinutes(10);
    public static final Duration TIME_AFTER_ORDER = Duration.ofDays(7);

    private MatchScorer() {
    }

    /** The pair's score. A different currency or an order with nothing left to pay is not a candidate: score 0, no reasons. */
    public static ScoredPair score(OrderFacts order, PaymentFacts payment, Recorder recorder, MatchConfig config) {
        long balance = order.balanceMinor();
        if (!order.currency().equals(payment.currency()) || balance <= 0) {
            return new ScoredPair(order.id(), payment.id(), 0, List.of(), Set.of(), false);
        }
        List<Reason> reasons = new ArrayList<>();
        Set<FloorCode> floors = EnumSet.of(FloorCode.SAME_CURRENCY);
        int total = 0;

        long paid = payment.amountMinor();
        if (paid == balance) {
            reasons.add(new Reason(ReasonCode.AMOUNT_EXACT, AMOUNT_EXACT_POINTS));
            floors.add(FloorCode.AMOUNT_EXACT);
            total += AMOUNT_EXACT_POINTS;
        } else if (paid < balance) {
            reasons.add(new Reason(ReasonCode.AMOUNT_PARTIAL, AMOUNT_PARTIAL_POINTS));
            total += AMOUNT_PARTIAL_POINTS;
        } else if (paid <= OVER_PAYMENT_MULTIPLE * balance) {
            reasons.add(new Reason(ReasonCode.AMOUNT_OVER, AMOUNT_OVER_POINTS));
            total += AMOUNT_OVER_POINTS;
        }

        if (payment.reference() != null && order.orderRef() != null && ReferenceKeys.same(payment.reference(), order.orderRef())) {
            if (order.referenceShared()) {
                reasons.add(new Reason(ReasonCode.REFERENCE_AMBIGUOUS, 0));
            } else {
                reasons.add(new Reason(ReasonCode.REFERENCE_MATCH, REFERENCE_POINTS));
                floors.add(FloorCode.REFERENCE_UNIQUE_MATCH);
                total += REFERENCE_POINTS;
            }
        }

        if (payment.senderPhoneHash() != null && payment.senderPhoneHash().equals(order.customerPhoneHash())) {
            reasons.add(new Reason(ReasonCode.IDENTITY_PHONE, IDENTITY_PHONE_POINTS));
            floors.add(FloorCode.IDENTITY_PRESENT);
            total += IDENTITY_PHONE_POINTS;
        } else {
            NameSimilarity.Agreement name = NameSimilarity.compare(payment.senderName(), order.customerName());
            if (name != NameSimilarity.Agreement.NONE) {
                int points = name == NameSimilarity.Agreement.FULL ? IDENTITY_NAME_FULL_POINTS : IDENTITY_NAME_INITIAL_POINTS;
                reasons.add(new Reason(name == NameSimilarity.Agreement.FULL ? ReasonCode.IDENTITY_NAME_FULL
                        : ReasonCode.IDENTITY_NAME_INITIAL, points));
                total += points;
                if (payment.senderLast4() != null && payment.senderLast4().equals(order.customerLast4())) {
                    reasons.add(new Reason(ReasonCode.IDENTITY_LAST4, IDENTITY_LAST4_POINTS));
                    total += IDENTITY_LAST4_POINTS;
                }
                floors.add(FloorCode.IDENTITY_PRESENT);
            }
        }

        if (payment.paidAt() != null && !payment.paidAt().isBefore(order.createdAt().minus(TIME_BEFORE_ORDER))
                && !payment.paidAt().isAfter(order.createdAt().plus(TIME_AFTER_ORDER))) {
            reasons.add(new Reason(ReasonCode.TIME_IN_WINDOW, TIME_POINTS));
            total += TIME_POINTS;
        }

        if (payment.hasTransactionId()) {
            floors.add(FloorCode.HAS_TRANSACTION_ID);
        } else if (total > config.autoThreshold() - 1) {
            total = config.autoThreshold() - 1;
            reasons.add(new Reason(ReasonCode.UNVERIFIED_CAPPED, 0));
        }
        if (recorder.trusted()) {
            floors.add(FloorCode.TRUSTED_RECORDER);
        }
        if (config.autoEnabled()) {
            floors.add(FloorCode.AUTO_ENABLED);
        }
        if (!order.flagged() && !payment.flagged()) {
            floors.add(FloorCode.NOT_FLAGGED);
        }
        boolean autoEligible = floors.size() == FloorCode.values().length && total >= config.autoThreshold();
        return new ScoredPair(order.id(), payment.id(), total, reasons, floors, autoEligible);
    }
}
