package dev.chatledger.security;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What is accepted as text from outside. The rule is to reject, not to repair: bytes that are not
 * valid UTF-8, a NUL, a control character (other than tab, and line breaks where a field is meant to
 * have them) and a broken percent sequence all make the whole request invalid. Servers by default
 * replace bad bytes with U+FFFD or drop them, which makes two parts of a system disagree about what
 * was sent; this refuses instead.
 */
public final class InputRules {

    private InputRules() {
    }

    /** A decoded form or query string; empty if any part of it is invalid. */
    public static Optional<Map<String, List<String>>> parseForm(String encoded) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return Optional.of(out);
        }
        for (String pair : encoded.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            Optional<String> name = decode(eq < 0 ? pair : pair.substring(0, eq), false);
            Optional<String> value = decode(eq < 0 ? "" : pair.substring(eq + 1), true);
            if (name.isEmpty() || value.isEmpty()) {
                return Optional.empty();
            }
            out.computeIfAbsent(name.get(), k -> new java.util.ArrayList<>()).add(value.get());
        }
        return Optional.of(out);
    }

    /** Valid UTF-8 text without NUL or control characters, apart from tab, LF and CR. */
    public static boolean validText(byte[] bytes) {
        return strictUtf8(bytes).filter(s -> controlFree(s, true)).isPresent();
    }

    /**
     * A single text field for use by controllers: trimmed, within the length, no control characters
     * (line breaks only if {@code allowLineBreaks}). Empty means "reject the request".
     */
    public static Optional<String> field(String raw, int maxChars, boolean allowLineBreaks) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maxChars || !controlFree(value, allowLineBreaks)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * Percent-decodes one name or value to bytes, then decodes those bytes as strict UTF-8. The input
     * is the raw text as received, one char per byte, so a raw byte above 0x7F (not allowed in
     * URL-encoded data) is rejected.
     */
    private static Optional<String> decode(String part, boolean allowLineBreaks) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(part.length());
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '%') {
                if (i + 2 >= part.length()) {
                    return Optional.empty();
                }
                int hi = Character.digit(part.charAt(i + 1), 16);
                int lo = Character.digit(part.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return Optional.empty();
                }
                bytes.write(hi * 16 + lo);
                i += 2;
            } else if (c == '+') {
                bytes.write(' ');
            } else if (c > 0x7f) {
                return Optional.empty();
            } else {
                bytes.write(c);
            }
        }
        return strictUtf8(bytes.toByteArray()).filter(s -> controlFree(s, allowLineBreaks));
    }

    private static Optional<String> strictUtf8(byte[] bytes) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    private static boolean controlFree(String s, boolean allowLineBreaks) {
        return s.codePoints().noneMatch(cp -> Character.isISOControl(cp)
                && !(cp == '\t' || (allowLineBreaks && (cp == '\n' || cp == '\r'))));
    }
}
