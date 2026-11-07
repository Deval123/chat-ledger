package dev.chatledger.payments;

import dev.chatledger.extraction.PaymentTemplateSpec;

import java.util.List;

/**
 * Where payment templates come from. Today the only source is the jar (files added by pull request and proven by the build).
 * The interface exists so a database source can be added later, with the same gates; see the task 5 design stop, decision D2.
 */
public interface PaymentTemplateSource {

    /** All template specs, bound strictly; throws a {@code TemplateException} (a code, never text) on any problem. */
    List<PaymentTemplateSpec> load();
}
