package dev.chatledger.security;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Short refusals with a fixed body that says nothing about the cause. */
final class PlainResponses {

    private PlainResponses() {
    }

    static void write(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    static void tooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        write(response, 429, "Too many requests. Try again shortly.");
    }
}
