package dev.chatledger.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Minimal health endpoint used by the container host and Docker health check.
 *
 * <p>Returns HTTP 200 with a JSON body so that the host can determine the
 * application is running before routing traffic to it.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
