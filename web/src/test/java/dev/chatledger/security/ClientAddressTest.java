package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client address of the per-address limits (task 9, D23): at 0 hops a spoofed header changes nothing; at N it is the Nth entry from
 * the right; anything odd falls back to the socket address. The bound and the startup sentence. No database.
 */
class ClientAddressTest {

    private static MockHttpServletRequest request(String socket, String... forwarded) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(socket);
        for (String f : forwarded) {
            r.addHeader("X-Forwarded-For", f);
        }
        return r;
    }

    @Test
    void at_zero_hops_a_spoofed_header_does_not_change_the_key() {
        assertThat(ClientAddress.of(request("10.0.0.5", "203.0.113.9"), 0)).isEqualTo("10.0.0.5");
        assertThat(ClientAddress.of(request("10.0.0.5", "1.1.1.1, 2.2.2.2"), 0)).isEqualTo("10.0.0.5");
        assertThat(ClientAddress.of(request("10.0.0.5"), 0)).isEqualTo("10.0.0.5");
    }

    @Test
    void above_zero_the_client_is_the_entry_that_many_hops_from_the_right() {
        // One proxy appended the address it saw; whatever the client wrote before it is to the left and ignored.
        assertThat(ClientAddress.of(request("10.0.0.5", "6.6.6.6, 203.0.113.9"), 1)).isEqualTo("203.0.113.9");
        assertThat(ClientAddress.of(request("10.0.0.5", "6.6.6.6, 203.0.113.9, 198.51.100.7"), 2)).isEqualTo("203.0.113.9");
        // Several headers are one list, in order.
        assertThat(ClientAddress.of(request("10.0.0.5", "6.6.6.6", "203.0.113.9"), 1)).isEqualTo("203.0.113.9");
        assertThat(ClientAddress.of(request("10.0.0.5", "2001:db8::1"), 1)).isEqualTo("2001:db8::1");
    }

    @Test
    void anything_odd_falls_back_to_the_socket_address() {
        assertThat(ClientAddress.of(request("10.0.0.5"), 1)).as("no header").isEqualTo("10.0.0.5");
        assertThat(ClientAddress.of(request("10.0.0.5", "203.0.113.9"), 2)).as("fewer entries than hops").isEqualTo("10.0.0.5");
        assertThat(ClientAddress.of(request("10.0.0.5", "not-an-address"), 1)).isEqualTo("10.0.0.5");
        assertThat(ClientAddress.of(request("10.0.0.5", "1.2.3.4 , "), 1)).as("an empty last entry").isEqualTo("10.0.0.5");
        assertThat(ClientAddress.of(request("10.0.0.5", "1.2.3.4<script>"), 1)).isEqualTo("10.0.0.5");
        assertThat(ClientAddress.of(request("10.0.0.5", "a".repeat(30) + ":" + "b".repeat(20)), 1)).as("too long").isEqualTo("10.0.0.5");
        assertThat(ClientAddress.ipLiteral("1234")).as("no separator").isFalse();
        assertThat(ClientAddress.ipLiteral("1")).isFalse();
        assertThat(ClientAddress.ipLiteral("::")).isTrue();
    }

    @Test
    void the_number_of_hops_is_bounded_and_named_at_startup() {
        assertThatThrownBy(() -> props(3)).hasMessageContaining("trusted-proxy-hops");
        assertThatThrownBy(() -> props(-1)).hasMessageContaining("trusted-proxy-hops");
        assertThat(props(2).trustedProxyHops()).isEqualTo(2);
        assertThat(StartupGuards.describeTrustedProxyHops(0)).contains("trusted-proxy-hops=0").contains("ignored");
        assertThat(StartupGuards.describeTrustedProxyHops(1)).contains("trusted-proxy-hops=1").contains("entry 1 from the right");
    }

    private static SecurityProperties props(int hops) {
        java.time.Duration ok = java.time.Duration.ofMinutes(5);
        var size = org.springframework.util.unit.DataSize.ofKilobytes(4);
        return new SecurityProperties(new SecurityProperties.Session(ok, ok, ok), new SecurityProperties.Hashing(1, ok),
                new SecurityProperties.Limits("1/1m", "1/1m", "1/1m", "1/1m", "1/1m", "1/1h", "1/1m", "1/1m"),
                new SecurityProperties.Size(size, size, size), ok, hops);
    }
}
