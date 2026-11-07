package dev.chatledger.payments;

import dev.chatledger.extraction.PaymentTemplates;
import dev.chatledger.extraction.TemplateException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the active payment templates at startup. Every template passes every gate, and the whole set is proven by its own
 * examples, or the application does not start: a broken template that was quietly skipped would turn a whole provider's
 * payments into unparsed ones without anyone noticing. The failure names the template id and a problem code, never the template
 * or any text.
 */
@Configuration
public class PaymentTemplateConfig {

    @Bean
    public PaymentTemplates paymentTemplates(PaymentTemplateSource source) {
        try {
            return PaymentTemplates.of(source.load());
        } catch (TemplateException e) {
            throw new IllegalStateException("Payment templates are not usable: " + e.getMessage(), null);
        }
    }
}
