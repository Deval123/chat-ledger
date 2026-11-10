package dev.chatledger.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The client's address for the per-address limits (task 9, D23).
 *
 * <p>With {@code app.security.trusted-proxy-hops = 0} (the default) it is the socket address and {@code X-Forwarded-For} is ignored:
 * anyone can write that header, and trusting it would let a client choose its own key and walk past the limit. With N trusted proxies in
 * front of the application, each appends the address it received the request from, so the last N entries of the header were written by
 * them and the Nth from the right is the client as the outermost trusted proxy saw it. Several headers are read in order, as one list.
 *
 * <p>Anything unexpected falls back to the socket address, never to a value the client wrote: fewer entries than trusted hops (the
 * request did not come through every proxy), or an entry that is not an IP literal.
 */
public final class ClientAddress {

    static final String HEADER = "X-Forwarded-For";
    /** Longest IP literal (an IPv6 address with an embedded IPv4 one). */
    private static final int MAX_LITERAL = 45;

    private ClientAddress() {
    }

    public static String of(HttpServletRequest request, int trustedHops) {
        String socket = request.getRemoteAddr();
        if (trustedHops <= 0) {
            return socket;
        }
        List<String> entries = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders(HEADER))) {
            for (String part : header.split(",", -1)) {
                entries.add(part.strip());
            }
        }
        if (entries.size() < trustedHops) {
            return socket;
        }
        String client = entries.get(entries.size() - trustedHops);
        return ipLiteral(client) ? client : socket;
    }

    /** Digits, hex letters, dots and colons only, 2 to 45 characters, and at least one dot or colon: an IPv4 or IPv6 literal. */
    static boolean ipLiteral(String value) {
        if (value.length() < 2 || value.length() > MAX_LITERAL) {
            return false;
        }
        boolean separator = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (c == '.' || c == ':') {
                separator = true;
            } else if (!hex) {
                return false;
            }
        }
        return separator;
    }
}
