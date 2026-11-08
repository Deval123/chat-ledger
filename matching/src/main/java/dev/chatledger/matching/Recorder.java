package dev.chatledger.matching;

/** Who put the payment into the system. A helper is the lower-trust role: a helper's payment is only ever suggested. */
public enum Recorder {
    SELLER, MACHINE, HELPER;

    public boolean trusted() {
        return this != HELPER;
    }
}
