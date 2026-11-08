package dev.chatledger.matching;

/**
 * Who put the payment into the system. A helper is the lower-trust role: a helper's payment is only ever suggested.
 *
 * <p>{@code MACHINE} is reserved for the signed machine feed (task 11), which will record an explicit machine identity in the audit
 * entry of the payment. Nothing stored today can produce it: a missing actor is a {@link #HELPER}, never the machine. The scorer
 * knows the value so that its rules for the future path are tested, and for no other reason.
 */
public enum Recorder {
    SELLER, MACHINE, HELPER;

    public boolean trusted() {
        return this != HELPER;
    }
}
