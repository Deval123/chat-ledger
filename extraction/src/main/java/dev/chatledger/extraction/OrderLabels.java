package dev.chatledger.extraction;

import java.util.Map;
import java.util.Set;

/**
 * The words the order parser recognises, as constants. English and French. Every entry is already in
 * {@link TextKeys#key(String) key form} (lower case, no accents), and a label from a message is compared with
 * them by equality after the same conversion. Nothing here is turned into a pattern, and nothing from a message
 * ever adds to these sets.
 */
final class OrderLabels {

    static final Set<String> ORDER = Set.of("order", "commande", "ref", "reference");
    static final Set<String> NAME = Set.of("name", "nom", "client", "customer");
    static final Set<String> PHONE = Set.of("phone", "tel", "telephone", "mobile");
    static final Set<String> TOTAL = Set.of("total", "montant", "montant total", "total amount");
    static final Set<String> DELIVERY = Set.of("deliver", "delivery", "deliver to", "livraison", "livrer");

    /** One-line format: words that start a segment and name the sender ("from Ana", "de Ana"). */
    static final Set<String> FROM = Set.of("from", "de");

    /**
     * Currency words and the tenant currency they stand for. {@code FCFA}, {@code F CFA} and {@code XAF} are the
     * same currency (the Central African franc). Any other three-letter code is kept as written so the validator
     * can refuse it when it is not the tenant's currency.
     */
    static final Map<String, String> CURRENCY_ALIASES = Map.of("XAF", "XAF", "FCFA", "XAF", "F CFA", "XAF");

    /** The multiplication signs between a quantity and an item name. */
    static final String TIMES = "xX\u00D7";

    private OrderLabels() {
    }
}
