package dev.chatledger.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A request whose body has already been read, checked and size-limited by {@link RequestGuardFilter}.
 * Everything downstream reads the same bytes. For a URL-encoded form the parameters come from the
 * checked parse, not from the container, which would read the original stream again and would repair
 * bad bytes silently.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final Map<String, String[]> parameters;

    /** @param parameters decoded form and query parameters, or null to leave parameters to the container */
    CachedBodyRequest(HttpServletRequest request, byte[] body, Map<String, List<String>> parameters) {
        super(request);
        this.body = body;
        if (parameters == null) {
            this.parameters = null;
        } else {
            Map<String, String[]> copy = new LinkedHashMap<>();
            parameters.forEach((k, v) -> copy.put(k, v.toArray(String[]::new)));
            this.parameters = Collections.unmodifiableMap(copy);
        }
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return in.read(); }
            @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {
                throw new IllegalStateException("asynchronous reads are not supported");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public String getCharacterEncoding() {
        return StandardCharsets.UTF_8.name();
    }

    @Override
    public String getParameter(String name) {
        if (parameters == null) {
            return super.getParameter(name);
        }
        String[] values = parameters.get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public String[] getParameterValues(String name) {
        return parameters == null ? super.getParameterValues(name) : parameters.get(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return parameters == null ? super.getParameterMap() : parameters;
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return parameters == null ? super.getParameterNames() : Collections.enumeration(new ArrayList<>(parameters.keySet()));
    }
}
