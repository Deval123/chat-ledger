package dev.chatledger.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the web module: starts the Spring context without a real
 * database and verifies the health endpoint responds with HTTP 200.
 *
 * <p>In Spring Boot 4.x the @AutoConfigureMockMvc annotation moved to
 * org.springframework.boot.webmvc.test.autoconfigure (in the
 * spring-boot-starter-webmvc-test dependency). DataSource, JPA, and
 * Flyway auto-configurations are excluded via application-test.properties
 * so this test runs in CI without a running PostgreSQL instance.
 *
 * <p>Note: @SpringBootTest no longer has an excludeAutoConfiguration attribute
 * in Boot 4.x. Exclusions are handled via spring.autoconfigure.exclude in the
 * test profile properties file, which is the recommended approach.
 *
 * <p>Requirement: 9 (health check for deployment).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_endpoint_returns_200() throws Exception {
        mockMvc.perform(get("/health"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("UP"));
    }
}
