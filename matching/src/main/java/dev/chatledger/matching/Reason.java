package dev.chatledger.matching;

import java.util.List;
import java.util.Objects;

/** One contribution to a score: a code and its points. */
public record Reason(ReasonCode code, int points) {

    public Reason {
        Objects.requireNonNull(code, "code");
        if (points < 0 || points > 100) {
            throw new IllegalArgumentException("points must be between 0 and 100");
        }
    }

    /**
     * The reasons as JSON for {@code match.reasons_json}: enum names and integers only, so no text from a message can be stored here
     * (the column also has a CHECK on its character set).
     */
    public static String toJson(List<Reason> reasons) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Reason r = reasons.get(i);
            sb.append("{\"code\":\"").append(r.code().name()).append("\",\"points\":").append(r.points()).append('}');
        }
        return sb.append(']').toString();
    }

    /** What {@link #fromJson} found: the reasons it knows, how many entries named a code it does not know, and whether the text was well formed. */
    public record Parsed(List<Reason> reasons, int unknown, boolean wellFormed) {
    }

    /**
     * Reads the text {@link #toJson} writes, and nothing else: {@code [{"code":"NAME","points":N},...]} with no spaces, a code made of
     * capital letters, digits and underscores, and points of one to three digits. Anything else is "not well formed" and yields no reasons; a code
     * that is well formed but not in {@link ReasonCode} is counted, never returned. The text is never interpreted, only matched piece by
     * piece (no pattern, no JSON library), so what a page prints from it is always an enum's name or an integer.
     */
    public static Parsed fromJson(String json) {
        List<Reason> out = new java.util.ArrayList<>();
        int unknown = 0;
        if (json == null || json.length() < 2 || json.length() > 2_000 || json.charAt(0) != '[' || json.charAt(json.length() - 1) != ']') {
            return new Parsed(List.of(), 0, false);
        }
        String body = json.substring(1, json.length() - 1);
        if (body.isEmpty()) {
            return new Parsed(List.of(), 0, true);
        }
        int at = 0;
        while (at < body.length()) {
            String open = "{\"code\":\"";
            if (!body.startsWith(open, at)) {
                return new Parsed(List.of(), 0, false);
            }
            int nameStart = at + open.length();
            int nameEnd = nameStart;
            while (nameEnd < body.length() && (body.charAt(nameEnd) == '_' || (body.charAt(nameEnd) >= 'A' && body.charAt(nameEnd) <= 'Z')
                    || (body.charAt(nameEnd) >= '0' && body.charAt(nameEnd) <= '9'))) {
                nameEnd++;
            }
            String middle = "\",\"points\":";
            if (nameEnd == nameStart || nameEnd - nameStart > 40 || !body.startsWith(middle, nameEnd)) {
                return new Parsed(List.of(), 0, false);
            }
            int digitsStart = nameEnd + middle.length();
            int digitsEnd = digitsStart;
            while (digitsEnd < body.length() && body.charAt(digitsEnd) >= '0' && body.charAt(digitsEnd) <= '9') {
                digitsEnd++;
            }
            if (digitsEnd == digitsStart || digitsEnd - digitsStart > 3 || digitsEnd >= body.length() || body.charAt(digitsEnd) != '}') {
                return new Parsed(List.of(), 0, false);
            }
            int points = Integer.parseInt(body.substring(digitsStart, digitsEnd));
            if (points > 100) {
                return new Parsed(List.of(), 0, false);
            }
            String name = body.substring(nameStart, nameEnd);
            ReasonCode code = null;
            for (ReasonCode candidate : ReasonCode.values()) {
                if (candidate.name().equals(name)) {
                    code = candidate;
                }
            }
            if (code == null) {
                unknown++;
            } else {
                out.add(new Reason(code, points));
            }
            at = digitsEnd + 1;
            if (at < body.length()) {
                if (body.charAt(at) != ',' || at + 1 >= body.length()) {
                    return new Parsed(List.of(), 0, false);
                }
                at++;
            }
        }
        return new Parsed(List.copyOf(out), unknown, true);
    }
}
