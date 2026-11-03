package dev.chatledger.extraction;

/**
 * Marker interface for values produced by the extraction pipeline.
 *
 * <p>Concrete subtypes are {@code ParsedOrder} and {@code ParsedPayment}.
 * Using a sealed hierarchy lets the compiler verify that all cases are handled.
 */
public sealed interface ParsedMessage
        permits ParsedOrder, ParsedPayment {
}
