package dev.chatledger.security;

/** Names of the session attributes the security code keeps. Values are timestamps only. */
final class SessionAttributes {

    /** When the user signed in; the absolute timeout counts from here. */
    static final String AUTHENTICATED_AT = "chatledger.authenticatedAt";

    /** When the user's status and role were last re-read from the database. */
    static final String STATUS_CHECKED_AT = "chatledger.statusCheckedAt";

    /** When a demo visitor's lease ends (task 9): the session ends then, whatever the absolute timeout says. */
    static final String ENDS_AT = "chatledger.endsAt";

    private SessionAttributes() {
    }
}
