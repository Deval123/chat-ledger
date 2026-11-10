package dev.chatledger.extraction;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * A phone number in canonical form ({@link PhoneNumbers}). The digits are private: the only things this object
 * gives out are the last four digits and a keyed hash. {@code toString} is masked, so putting a phone in a log
 * line, an exception message or a record's {@code toString} cannot leak the number.
 */
public final class Phone {

    private final String digits;

    private Phone(String digits) {
        this.digits = digits;
    }

    /** A phone from any spelling, or empty if {@link PhoneNumbers#canonicalDigits} refuses it. */
    public static Optional<Phone> of(String raw) {
        return PhoneNumbers.canonicalDigits(raw).map(Phone::new);
    }

    /** The canonical digits every synthetic number starts with (the repository's convention, {@code +999}; task 9, D11). */
    public static final String SYNTHETIC_PREFIX = "999";

    /** True if this is a number of the synthetic range. A demo workspace accepts no other (requirement 7.3). */
    public boolean inSyntheticRange() {
        return digits.startsWith(SYNTHETIC_PREFIX);
    }

    /** The last four canonical digits, for display. */
    public String last4() {
        return digits.substring(digits.length() - 4);
    }

    /** HMAC-SHA-256 (hex, 64 characters) of the canonical digits under {@code key}. The same function for every caller. */
    public String keyedHash(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(digits.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is not available", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Phone that && digits.equals(that.digits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(digits);
    }

    @Override
    public String toString() {
        return "Phone[****" + last4() + "]";
    }
}
