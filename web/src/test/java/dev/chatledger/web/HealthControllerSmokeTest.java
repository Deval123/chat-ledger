package dev.chatledger.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the web module: starts the Spring context without a database and verifies that the
 * public health endpoint answers. DataSource, JPA and Flyway are excluded in application-test.properties; the
 * security beans only need a DataSource object to be constructed, so a mock stands in. Nothing here
 * touches a database, so it runs without Docker.
 *
 * <p>Requirement: 9 (health check for deployment).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerSmokeTest {

    @MockitoBean
    DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_endpoint_returns_200() throws Exception {
        mockMvc.perform(get("/health"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("UP"));
    }
}
