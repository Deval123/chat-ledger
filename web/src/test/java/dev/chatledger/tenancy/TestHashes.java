package dev.chatledger.tenancy;

/**
 * A stored-hash value for fixtures. It has the shape the database requires of an
 * Argon2id hash (V013) but is not the hash of any password, so no login can succeed
 * with it. Tests that need a working login create a real hash with the real encoder.
 */
public final class TestHashes {

    /** Well-formed, unusable: random-looking base64 that no password produces. */
    public static final String UNUSABLE =
            "{argon2id}$argon2id$v=19$m=19456,t=2,p=1$dW51c2FibGVzYWx0MDAwMA$dW51c2FibGVoYXNoMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA";

    private TestHashes() {
    }
}
