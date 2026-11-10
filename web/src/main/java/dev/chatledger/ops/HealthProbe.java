package dev.chatledger.ops;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@code java -jar app.jar health-probe [--port N]} (task 13): the container health check of an image that has no shell and no
 * {@code wget}. One {@code GET http://127.0.0.1:<port>/health} with a three-second timeout; exit 0 for a 200 whose body says {@code UP},
 * 1 for anything else. No Spring context starts, nothing is printed but one word, and it talks to the loopback address only.
 */
public final class HealthProbe {

    public static final String NAME = "health-probe";
    static final int DEFAULT_PORT = 8080;

    private HealthProbe() {
    }

    public static int main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length == 3 && "--port".equals(args[1])) {
            try {
                port = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                port = -1;
            }
        } else if (args.length != 1) {
            port = -1;
        }
        if (port < 1 || port > 65535) {
            System.err.println("usage: health-probe [--port 1..65535]");
            return 2;
        }
        boolean up = check(port, Duration.ofSeconds(3));
        System.out.println(up ? "UP" : "DOWN");
        return up ? 0 : 1;
    }

    static boolean check(int port, Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).timeout(timeout).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"UP\"");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
