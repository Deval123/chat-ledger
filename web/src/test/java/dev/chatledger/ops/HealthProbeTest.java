package dev.chatledger.ops;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The container health check answers yes only for a 200 that says UP, on the loopback address (task 13). No database. */
class HealthProbeTest {

    private static HttpServer serve(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void up_only_for_a_200_that_says_up() throws IOException {
        HttpServer up = serve(200, "{\"status\":\"UP\"}");
        HttpServer down = serve(503, "{\"status\":\"UP\"}");
        HttpServer odd = serve(200, "{\"status\":\"DOWN\"}");
        try {
            assertThat(HealthProbe.check(up.getAddress().getPort(), Duration.ofSeconds(2))).isTrue();
            assertThat(HealthProbe.check(down.getAddress().getPort(), Duration.ofSeconds(2))).isFalse();
            assertThat(HealthProbe.check(odd.getAddress().getPort(), Duration.ofSeconds(2))).isFalse();
        } finally {
            up.stop(0);
            down.stop(0);
            odd.stop(0);
        }
        assertThat(HealthProbe.check(1, Duration.ofMillis(500))).as("nothing listening").isFalse();
    }

    @Test
    void a_redirect_is_not_followed_even_to_a_page_that_says_up() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            byte[] bytes = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            assertThat(HealthProbe.check(server.getAddress().getPort(), Duration.ofSeconds(2))).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void the_exit_code_is_0_for_up_and_1_for_down() throws IOException {
        HttpServer down = serve(503, "{\"status\":\"DOWN\"}");
        try {
            assertThat(HealthProbe.main(new String[]{"health-probe", "--port", Integer.toString(down.getAddress().getPort())})).isEqualTo(1);
        } finally {
            down.stop(0);
        }
        assertThat(HealthProbe.main(new String[]{"health-probe", "--port", "1"})).as("nothing listening").isEqualTo(1);
    }

    @Test
    void the_arguments_are_bounded() throws IOException {
        HttpServer up = serve(200, "{\"status\":\"UP\"}");
        try {
            assertThat(HealthProbe.main(new String[]{"health-probe", "--port", Integer.toString(up.getAddress().getPort())})).isZero();
        } finally {
            up.stop(0);
        }
        assertThat(HealthProbe.main(new String[]{"health-probe", "--port", "0"})).isEqualTo(2);
        assertThat(HealthProbe.main(new String[]{"health-probe", "--port", "70000"})).isEqualTo(2);
        assertThat(HealthProbe.main(new String[]{"health-probe", "--port", "x"})).isEqualTo(2);
        assertThat(HealthProbe.main(new String[]{"health-probe", "--host", "example.test"})).isEqualTo(2);
    }
}
