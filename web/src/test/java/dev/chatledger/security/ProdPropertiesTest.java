package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The shipped {@code application-prod.properties}, over {@code application.properties} as Spring layers them, passes the startup guards
 * and says what the deployment relies on (task 13, D4, D6, D10, D18): Flyway off, no forwarded-header rewriting, HSTS always, relative
 * redirects, the secrets read from {@code /run/secrets/}, the actuator on its own port on the loopback address. {@code ProdProfileIT}
 * starts the application with this profile, but its test infrastructure sets some of these keys itself; this test reads the files alone.
 */
class ProdPropertiesTest {

    private static StandardEnvironment prod() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new PropertiesPropertySource("prod",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-prod.properties"))));
        env.getPropertySources().addLast(new PropertiesPropertySource("base",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));
        env.setActiveProfiles("prod");
        return env;
    }

    @Test
    void the_prod_files_pass_the_startup_guards() throws IOException {
        StandardEnvironment env = prod();
        assertThatCode(() -> new StartupGuards(env).checkProfiles()).doesNotThrowAnyException();
        assertThatCode(() -> new StartupGuards(env).checkSessionCookie()).doesNotThrowAnyException();
    }

    @Test
    void the_prod_profile_says_what_the_deployment_relies_on() throws IOException {
        StandardEnvironment env = prod();
        assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("none");
        assertThat(env.getProperty("app.security.hsts")).isEqualTo("always");
        assertThat(env.getProperty("server.tomcat.use-relative-redirects")).isEqualTo("true");
        assertThat(env.getProperty("spring.config.import")).isEqualTo("optional:configtree:/run/secrets/");
        assertThat(env.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        Integer management = env.getProperty("management.server.port", Integer.class);
        assertThat(management).as("the actuator leaves the public port").isNotNull().isNotEqualTo(env.getProperty("server.port", Integer.class));
        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class)).as("D7 and max_connections=30").isLessThanOrEqualTo(8);
        assertThat(env.getProperty("server.tomcat.threads.max", Integer.class)).as("D7").isLessThanOrEqualTo(40);
    }
}
