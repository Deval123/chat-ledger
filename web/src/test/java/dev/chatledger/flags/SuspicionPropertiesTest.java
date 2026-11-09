package dev.chatledger.flags;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.SuspicionConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The suspicion settings as Spring binds them: the approved defaults, a bad value stops startup, a disabled rule is named in the log. Task 7. */
class SuspicionPropertiesTest {

    private static SuspicionProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("app.suspicion", SuspicionProperties.class);
    }

    @Test
    void the_defaults_are_the_approved_ones_and_every_rule_is_on() {
        assertThat(bind(Map.of()).toConfig()).isEqualTo(SuspicionConfig.defaults());
    }

    @Test
    void a_value_out_of_range_or_an_unknown_code_stops_startup() {
        for (Map<String, String> bad : java.util.List.of(Map.of("app.suspicion.burst-count", "1"), Map.of("app.suspicion.usual-age", "40d"),
                Map.of("app.suspicion.overpay-min-bp", "50"), Map.of("app.suspicion.before-order-tolerance", "1m"))) {
            assertThatThrownBy(() -> bind(bad).toConfig()).as(bad.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> bind(Map.of("app.suspicion.disabled", "NOT_A_RULE"))).isInstanceOf(BindException.class);
    }

    @Test
    void a_rule_switched_off_by_the_operator_is_off_and_named_in_the_log() {
        SuspicionProperties p = bind(Map.of("app.suspicion.disabled", "SENDER_BURST,OVERPAYMENT"));
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger("suspicion.config");
        logger.addAppender(logs);
        try {
            var suspicion = new SuspicionWiring().suspicion(p);
            assertThat(suspicion.config().enabled(FlagCode.SENDER_BURST)).isFalse();
            assertThat(suspicion.config().enabled(FlagCode.OVERPAYMENT)).isFalse();
            assertThat(suspicion.config().enabled(FlagCode.DUPLICATE_TX)).isTrue();
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly("suspicion rules switched off by configuration: [OVERPAYMENT, SENDER_BURST]");
    }
}
