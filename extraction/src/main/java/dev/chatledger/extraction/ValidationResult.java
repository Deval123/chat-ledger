package dev.chatledger.extraction;

/** What the validator decided: fine, fine but the seller must look, or refuse (the message is kept as unparsed). */
public record ValidationResult(Verdict verdict, FailureReason reason) {

    public enum Verdict { OK, NEEDS_REVIEW, REJECT }

    public static ValidationResult ok() {
        return new ValidationResult(Verdict.OK, null);
    }

    public static ValidationResult review(FailureReason reason) {
        return new ValidationResult(Verdict.NEEDS_REVIEW, reason);
    }

    public static ValidationResult reject(FailureReason reason) {
        return new ValidationResult(Verdict.REJECT, reason);
    }
}
