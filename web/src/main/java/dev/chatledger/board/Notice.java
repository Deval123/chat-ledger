package dev.chatledger.board;

import dev.chatledger.matching.MatchingService;

import java.util.Optional;

/**
 * The one-sentence answers shown after an action. A notice travels as its code only (a query parameter on the redirect), is read back
 * through {@link #parse}, which accepts nothing but a name of this enum, and is shown from {@code messages.properties} as
 * {@code notice.CODE}. No sentence is built from request text, and no amount or name is carried.
 */
public enum Notice {
    ORDER_RECORDED, ORDER_NEEDS_REVIEW, ORDER_UNPARSED, ORDER_REPEAT, ORDER_REPEAT_DIFFERENT, ORDER_INPUT_REJECTED,
    PAYMENT_RECORDED, PAYMENT_UNVERIFIED, PAYMENT_DUPLICATE, PAYMENT_UNPARSED, PAYMENT_REPEAT, PAYMENT_REPEAT_DIFFERENT, PAYMENT_INPUT_REJECTED,
    MATCH_CONFIRMED, MATCH_NOT_APPLIED, MATCH_REJECTED, MATCH_MANUAL, MATCH_UNDONE, PAYMENT_SET_ASIDE, PAYMENT_TAKEN_BACK, REMATCHED,
    ORDER_APPROVED, APPROVE_NOT_REVIEWABLE, APPROVE_TOTAL_INVALID, APPROVE_NOT_A_NUMBER,
    REFUSED_NOT_FOUND, REFUSED_PAYMENT_NOT_FREE, REFUSED_ORDER_NOT_OPEN, REFUSED_CURRENCY_MISMATCH, REFUSED_NOTHING_LEFT_TO_PAY,
    REFUSED_AUTO_NOT_ELIGIBLE, REFUSED_NOT_A_SELLER, REFUSED_NOT_SUGGESTED, REFUSED_NOT_ACTIVE, REFUSED_SELLER_DECLINED,
    REFUSED_PAYMENT_STATUS_NOT_ALLOWED, EXPORT_TOO_LARGE, EXPORT_BAD_DATES;

    /** The notice named by {@code code}, or empty for anything else (including null). */
    public static Optional<Notice> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (Notice notice : values()) {
            if (notice.name().equals(code)) {
                return Optional.of(notice);
            }
        }
        return Optional.empty();
    }

    /** The sentence for a refusal of the matching service. */
    public static Notice refusal(MatchingService.Reason reason) {
        return valueOf("REFUSED_" + reason.name());
    }

    /** The message key. */
    public String key() {
        return "notice." + name();
    }
}
