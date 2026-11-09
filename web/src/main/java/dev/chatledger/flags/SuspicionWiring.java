package dev.chatledger.flags;

import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.Suspicion;
import dev.chatledger.suspicion.SuspicionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TreeSet;

/** Makes the validated rules available. A bad setting fails here, at startup; a rule switched off is named in the log (codes only). */
@Configuration
@EnableConfigurationProperties(SuspicionProperties.class)
public class SuspicionWiring {
    private static final Logger LOG = LoggerFactory.getLogger("suspicion.config");

    @Bean
    public Suspicion suspicion(SuspicionProperties properties) {
        SuspicionConfig config = properties.toConfig();
        if (!config.disabled().isEmpty()) {
            TreeSet<String> names = new TreeSet<>();
            for (FlagCode code : config.disabled()) {
                names.add(code.name());
            }
            LOG.warn("suspicion rules switched off by configuration: {}", names);
        }
        return new Suspicion(config);
    }
}
