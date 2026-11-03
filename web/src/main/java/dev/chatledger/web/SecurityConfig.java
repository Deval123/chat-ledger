package dev.chatledger.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Application-wide security configuration.
 *
 * <p>Rules defined here override Spring Boot's default.
 * CSRF, output encoding, session management, and CSP will be tightened
 * progressively in task 3. For now the skeleton secures the surface:
 * only /health and /actuator/health are public; everything else requires login.
 *
 * <p>Machine endpoints (task 11) will authenticate via HMAC signature on the
 * request body, not HTTP Basic. HTTP Basic is therefore not enabled here.
 *
 * <p>Requirement: 9 (Security and privacy).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Health endpoint is public so the container host can probe it.
                .requestMatchers("/health", "/actuator/health").permitAll()
                // Everything else requires authentication.
                .anyRequest().authenticated()
            )
            // Form login will be replaced with a proper login page in task 3.
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
            );

        return http.build();
    }
}
