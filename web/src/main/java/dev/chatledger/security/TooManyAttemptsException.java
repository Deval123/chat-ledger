package dev.chatledger.security;

/** A sign-in attempt refused by the per-account limit. Carries no account detail. */
final class TooManyAttemptsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;
    private final String clientAddress;

    TooManyAttemptsException(long retryAfterSeconds, String clientAddress) {
        super("too many attempts", null, false, false);
        this.retryAfterSeconds = retryAfterSeconds;
        this.clientAddress = clientAddress;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    String clientAddress() {
        return clientAddress;
    }
}
