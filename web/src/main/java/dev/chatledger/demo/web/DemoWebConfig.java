package dev.chatledger.demo.web;

import dev.chatledger.demo.DemoMessages;
import dev.chatledger.orders.OrderIngestionService;
import dev.chatledger.payments.PaymentIngestionService;
import dev.chatledger.payments.PaymentTemplateSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;

/** The demo's beans, on the demo deployment only. The generator reads the same template specs the payment parser was proven with. */
@Configuration
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoWebConfig {

    @Bean
    public DemoMessages demoMessages(PaymentTemplateSource templates, Clock clock) {
        return new DemoMessages(templates.load(), clock, new SecureRandom());
    }

    @Bean
    public DemoScenarios demoScenarios(OrderIngestionService orders, PaymentIngestionService payments, DemoMessages messages) {
        return new DemoScenarios(orders, payments, messages);
    }
}
