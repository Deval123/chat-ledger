package dev.chatledger.matching;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Makes the validated {@link MatchConfig} available to the matcher. A bad setting fails here, at startup. */
@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
public class MatchingConfig {

    @Bean
    public MatchConfig matchConfig(MatchingProperties properties) {
        return properties.toConfig();
    }
}
