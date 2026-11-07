package dev.chatledger.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Size, encoding and content-type rules at the front of the chain, including bodies that declare no
 * length (chunked), where a limit on the header alone would not help.
 *
 * <p>Requirement: 9.1. Task 3.9.
 */
class RequestGuardFilterTest {

    private final SecurityEvents events = Mockito.mock(SecurityEvents.class);
    private final RequestGuardFilter filter = new RequestGuardFilter(
            new SecurityProperties.Size(DataSize.ofBytes(100), DataSize.ofBytes(200), DataSize.ofBytes(300)), events);

    private final AtomicReference<jakarta.servlet.http.HttpServletRequest> passed = new AtomicReference<>();
    private final FilterChain chain = (request, response) -> passed.set((jakarta.servlet.http.HttpServletRequest) request);

    private MockHttpServletRequest post(String path, String contentType, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setContentType(contentType);
        request.setContent(body);
        return request;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static byte[] bytes(int n) {
        return "a=".concat("x".repeat(n - 2)).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void a_body_within_the_limit_passes_and_its_parameters_are_the_checked_ones() throws Exception {
        MockHttpServletRequest request = post("/login", "application/x-www-form-urlencoded", "a=1&b=%C3%A9".getBytes(StandardCharsets.UTF_8));
        request.setQueryString("q=2");
        assertThat(run(request).getStatus()).isEqualTo(200);
        assertThat(passed.get().getParameter("a")).isEqualTo("1");
        assertThat(passed.get().getParameter("b")).isEqualTo("\u00e9");
        assertThat(passed.get().getParameter("q")).isEqualTo("2");
        assertThat(new String(passed.get().getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("a=1&b=%C3%A9");
    }

    @Test
    void the_limit_depends_on_the_path_and_the_content_type() throws Exception {
        assertThat(run(post("/login", "application/x-www-form-urlencoded", bytes(100))).getStatus()).isEqualTo(200);
        assertThat(run(post("/login", "application/x-www-form-urlencoded", bytes(101))).getStatus()).isEqualTo(413);
        assertThat(run(post("/x", "application/json", new byte[200])).getStatus()).isEqualTo(400);   // NULs: encoding, not size
        assertThat(run(post("/x", "application/json", "{}".repeat(100).getBytes(StandardCharsets.UTF_8))).getStatus()).isEqualTo(200);
        assertThat(run(post("/x", "application/json", "{}".repeat(101).getBytes(StandardCharsets.UTF_8))).getStatus()).isEqualTo(413);
        assertThat(run(post("/x", "application/x-www-form-urlencoded", bytes(300))).getStatus()).isEqualTo(200);
        assertThat(run(post("/x", "application/x-www-form-urlencoded", bytes(301))).getStatus()).isEqualTo(413);
    }

    @Test
    void a_declared_length_over_the_limit_is_refused_without_reading_the_body() throws Exception {
        MockHttpServletRequest request = post("/login", "application/x-www-form-urlencoded", new byte[0]);
        request.addHeader("Content-Length", "100000");
        request.setContent(null);
        // The request would fail if anything touched the stream.
        request.setContent(new byte[0]);
        MockHttpServletRequest declared = new MockHttpServletRequest("POST", "/login") {
            @Override public long getContentLengthLong() { return 100_000; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("body was read"); }
        };
        declared.setServletPath("/login");
        declared.setContentType("application/x-www-form-urlencoded");
        MockHttpServletResponse response = run(declared);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Connection")).isEqualTo("close");
        assertThat(passed.get()).isNull();
    }

    @Test
    void a_chunked_body_with_no_declared_length_is_counted_as_it_is_read() throws Exception {
        byte[] big = bytes(5000);
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/login") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                var in = new ByteArrayInputStream(big);
                return new jakarta.servlet.ServletInputStream() {
                    @Override public int read() { return in.read(); }
                    @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                    @Override public boolean isFinished() { return in.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(jakarta.servlet.ReadListener l) { }
                };
            }
        };
        chunked.setServletPath("/login");
        chunked.setContentType("application/x-www-form-urlencoded");
        chunked.addHeader("Transfer-Encoding", "chunked");
        assertThat(run(chunked).getStatus()).isEqualTo(413);
        assertThat(passed.get()).isNull();
    }

    @Test
    void bad_encodings_get_400_in_the_query_the_form_and_json() throws Exception {
        MockHttpServletRequest query = new MockHttpServletRequest("GET", "/x");
        query.setQueryString("a=%FF");
        assertThat(run(query).getStatus()).isEqualTo(400);

        assertThat(run(post("/x", "application/x-www-form-urlencoded", "a=%00".getBytes(StandardCharsets.UTF_8))).getStatus()).isEqualTo(400);
        assertThat(run(post("/x", "application/x-www-form-urlencoded", "a=%FF".getBytes(StandardCharsets.UTF_8))).getStatus()).isEqualTo(400);
        assertThat(run(post("/x", "application/json", new byte[]{'{', (byte) 0xC3, '}'})).getStatus()).isEqualTo(400);
        assertThat(passed.get()).isNull();
    }

    @Test
    void multipart_is_refused() throws Exception {
        assertThat(run(post("/x", "multipart/form-data; boundary=x", "--x--".getBytes(StandardCharsets.UTF_8))).getStatus()).isEqualTo(415);
    }

    @Test
    void a_request_without_a_body_goes_straight_through() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/app");
        assertThat(run(get).getStatus()).isEqualTo(200);
        assertThat(passed.get()).isSameAs(get);
    }

    @Test
    void a_refusal_says_nothing_about_the_cause_and_is_logged_with_a_fixed_label_only() throws Exception {
        MockHttpServletResponse response = run(post("/x", "application/x-www-form-urlencoded", "secret=%00".getBytes(StandardCharsets.UTF_8)));
        assertThat(response.getContentAsString()).isEqualTo("Bad request.");
        Mockito.verify(events).requestRejected("form-encoding");
    }
}
