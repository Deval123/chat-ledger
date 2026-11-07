package dev.chatledger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The first thing every request meets inside the security chain, before anything reads its body.
 *
 * <ul>
 *   <li><b>Size.</b> A declared length above the limit gets 413 without reading a byte. A body with no
 *       declared length (chunked) is read at most up to the limit and refused with 413 if there is more.
 *       Limits: login 4 KiB, JSON 16 KiB, everything else 64 KiB (configurable).</li>
 *   <li><b>Encoding.</b> The query string, a URL-encoded form and a JSON body must be valid UTF-8 with no
 *       NUL or control characters, or the request gets 400. Nothing is repaired.</li>
 *   <li><b>No multipart.</b> The application has no upload; {@code multipart/*} gets 415.</li>
 * </ul>
 *
 * <p>The body is read once and handed on, so the later filters (CSRF reads a form field) and the
 * controllers see exactly the bytes that were checked.
 */
public class RequestGuardFilter extends OncePerRequestFilter {

    private final SecurityProperties.Size size;
    private final SecurityEvents events;

    public RequestGuardFilter(SecurityProperties.Size size, SecurityEvents events) {
        this.size = size;
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String query = request.getQueryString();
        Optional<Map<String, List<String>>> queryParameters = InputRules.parseForm(query);
        if (queryParameters.isEmpty()) {
            reject(response, 400, "query-encoding");
            return;
        }

        String contentType = request.getContentType() == null ? "" : request.getContentType().toLowerCase(Locale.ROOT);
        long limit = limitFor(request, contentType);
        long declared = request.getContentLengthLong();
        boolean chunked = request.getHeader("Transfer-Encoding") != null;
        if (declared > limit) {
            response.setHeader("Connection", "close");
            reject(response, 413, "declared-size");
            return;
        }
        if (declared <= 0 && !chunked) {
            chain.doFilter(request, response);
            return;
        }
        if (contentType.startsWith("multipart/")) {
            reject(response, 415, "multipart");
            return;
        }

        byte[] body = readAtMost(request.getInputStream(), limit);
        if (body == null) {
            response.setHeader("Connection", "close");
            reject(response, 413, "streamed-size");
            return;
        }

        Map<String, List<String>> parameters = null;
        if (contentType.startsWith("application/x-www-form-urlencoded")) {
            Optional<Map<String, List<String>>> form = InputRules.parseForm(new String(body, java.nio.charset.StandardCharsets.ISO_8859_1));
            if (form.isEmpty()) {
                reject(response, 400, "form-encoding");
                return;
            }
            Map<String, List<String>> merged = new LinkedHashMap<>();
            queryParameters.get().forEach((k, v) -> merged.put(k, new java.util.ArrayList<>(v)));
            form.get().forEach((k, v) -> merged.computeIfAbsent(k, x -> new java.util.ArrayList<>()).addAll(v));
            parameters = merged;
        } else if (contentType.contains("json") && !InputRules.validText(body)) {
            reject(response, 400, "json-encoding");
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body, parameters), response);
    }

    private long limitFor(HttpServletRequest request, String contentType) {
        if ("/login".equals(request.getServletPath())) {
            return size.login().toBytes();
        }
        return (contentType.contains("json") ? size.json() : size.other()).toBytes();
    }

    /** The whole stream if it has at most {@code limit} bytes, otherwise null (without reading the rest). */
    private static byte[] readAtMost(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > limit) {
                return null;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private void reject(HttpServletResponse response, int status, String reason) throws IOException {
        events.requestRejected(reason);
        PlainResponses.write(response, status, switch (status) {
            case 413 -> "Request too large.";
            case 415 -> "Unsupported content type.";
            default -> "Bad request.";
        });
    }
}
