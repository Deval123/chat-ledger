package dev.chatledger.security;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A minimal browser for tests against the real server: keeps cookies, never follows redirects, and
 * sends cookies whatever their Secure flag (the test server speaks plain HTTP, the cookie is still
 * issued as Secure so its flags can be checked).
 */
final class Browser {

    record Response(int status, HttpHeaders headers, String body) {
        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        List<String> setCookies() {
            return headers.allValues("set-cookie");
        }
    }

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    private final int port;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    Browser(int port) {
        this.port = port;
    }

    String cookie(String name) {
        return cookies.get(name);
    }

    void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    Map<String, String> cookies() {
        return Map.copyOf(cookies);
    }

    Response get(String path, String... headers) {
        return send(builder(path, headers).GET().build());
    }

    Response post(String path, Map<String, String> form, String... headers) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(builder(path, headers).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    /**
     * A POST with a body exactly as given. With {@code chunked} the length is not declared (the JDK client then
     * sends Transfer-Encoding: chunked), as an attacker would to get past a check on Content-Length.
     */
    Response postRaw(String path, String contentType, byte[] body, boolean chunked, String... headers) {
        HttpRequest.BodyPublisher publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
        return send(builder(path, headers).header("Content-Type", contentType).POST(publisher).build());
    }

    /** The CSRF token of the first form on the page, or null. */
    static String csrf(Response page) {
        Matcher m = CSRF.matcher(page.body());
        return m.find() ? m.group(1) : null;
    }

    private HttpRequest.Builder builder(String path, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        if (!cookies.isEmpty()) {
            b.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        return b;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
            for (String setCookie : r.headers().allValues("set-cookie")) {
                remember(setCookie);
            }
            return new Response(r.statusCode(), r.headers(), r.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void remember(String setCookie) {
        List<String> parts = new ArrayList<>(List.of(setCookie.split(";")));
        String[] nameValue = parts.get(0).trim().split("=", 2);
        boolean expired = parts.stream().skip(1).map(p -> p.trim().toLowerCase(Locale.ROOT))
                .anyMatch(p -> p.equals("max-age=0"));
        if (expired || nameValue.length < 2 || nameValue[1].isEmpty()) {
            cookies.remove(nameValue[0]);
        } else {
            cookies.put(nameValue[0], nameValue[1]);
        }
    }
}
