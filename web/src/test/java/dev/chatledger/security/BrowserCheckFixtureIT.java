package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Not a test. A fixture for the developer tool in {@code tools/browser-check}: with {@code -Dbrowser.check=true} it starts the application on
 * a throwaway database, creates a workspace with a seller and a helper (synthetic), writes the address and the sign-in details to
 * {@code /tmp/browser-check.json} and waits (at most fifteen minutes) for the file {@code /tmp/browser-check.done}. Without the property it is
 * skipped, so the build and CI never run it.
 */
@EnabledIfSystemProperty(named = "browser.check", matches = "true")
class BrowserCheckFixtureIT extends AbstractWebIT {

    @Test
    void serve_until_the_browser_check_is_done() throws Exception {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Path done = Path.of("/tmp/browser-check.done");
        Files.deleteIfExists(done);
        String json = "{\"port\":" + port + ",\"slug\":\"" + w.slug() + "\",\"seller\":{\"email\":\"" + w.email() + "\",\"password\":\"" + w.password()
                + "\"},\"helper\":{\"email\":\"" + helper.email() + "\",\"password\":\"" + helper.password() + "\"}}";
        Files.writeString(Path.of("/tmp/browser-check.json"), json);
        long end = System.nanoTime() + Duration.ofMinutes(15).toNanos();
        while (!Files.exists(done) && System.nanoTime() < end) {
            Thread.sleep(500);
        }
        Files.deleteIfExists(Path.of("/tmp/browser-check.json"));
        Files.deleteIfExists(done);
    }
}
