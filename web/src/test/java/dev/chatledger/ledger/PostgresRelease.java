package dev.chatledger.ledger;

/**
 * The PostgreSQL release every integration test runs on, and the one the deployment's {@code db} service runs: the same tag and digest
 * (task 13, D9). A plain constant, so a unit test can compare the compose file with it without reaching any container code.
 */
public final class PostgresRelease {

    public static final String IMAGE =
            "postgres:16.15-alpine@sha256:aa6cb33cf4c19c765cefcf48d96176258800870a9a1e930ad126200f9d825139";

    private PostgresRelease() {
    }
}
