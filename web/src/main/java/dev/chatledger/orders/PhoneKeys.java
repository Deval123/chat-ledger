package dev.chatledger.orders;

import dev.chatledger.extraction.Phone;
import dev.chatledger.security.KeyedHasher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one place a customer's phone becomes the value stored in {@code customer.phone_hash}: HMAC-SHA-256 of the
 * canonical digits ({@link dev.chatledger.extraction.PhoneNumbers}) under a key derived for that tenant from the
 * application secret. Tasks 5 and 6 find a payer from an SMS with the same method, so one person is one customer.
 *
 * <p>The key is not in the database. Because a phone number has few possibilities, a hash anyone can recompute would
 * be useless; this one cannot be recomputed without the application secret. Each tenant has its own key, so the same
 * number in two tenants gives two unrelated hashes.
 */
@Component
public class PhoneKeys {

    private final KeyedHasher hasher;

    public PhoneKeys(KeyedHasher hasher) {
        this.hasher = hasher;
    }

    public byte[] keyFor(UUID tenantId) {
        return hasher.derive("chatledger/phone-hash/v1/" + tenantId);
    }

    /** The value for {@code customer.phone_hash}. */
    public String hash(UUID tenantId, Phone phone) {
        return phone.keyedHash(keyFor(tenantId));
    }
}
