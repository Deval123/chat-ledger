package dev.chatledger.security;

import dev.chatledger.demo.ops.DemoWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Not a test. A fixture for {@code tools/browser-check/demo-walk.mjs} (task 9): with {@code -Dbrowser.demo=true} it starts the application
 * as the demo deployment runs it, on the demo database, with six fresh seeded workspaces in the pool, writes the address to
 * {@code /tmp/browser-demo.json} and waits (at most fifteen minutes) for {@code /tmp/browser-demo.done}. The walk enters the demo with the
 * button, like a judge; no credentials exist. Without the property it is skipped, so the build and CI never run it.
 */
@EnabledIfSystemProperty(named = "browser.demo", matches = "true")
class DemoBrowserFixtureIT extends AbstractDemoWebIT {

    @Test
    void serve_until_the_demo_walk_is_done() throws Exception {
        DemoWorld.retireEverythingWaiting();
        for (int i = 0; i < 6; i++) {
            DemoWorld.seeded();
        }
        Path done = Path.of("/tmp/browser-demo.done");
        Files.deleteIfExists(done);
        Files.writeString(Path.of("/tmp/browser-demo.json"), "{\"port\":" + port + "}");
        long end = System.nanoTime() + Duration.ofMinutes(15).toNanos();
        while (!Files.exists(done) && System.nanoTime() < end) {
            Thread.sleep(500);
            clock.advance(Duration.ofMillis(500));   // the application's clock follows real time while the walk runs
        }
        Files.deleteIfExists(Path.of("/tmp/browser-demo.json"));
        Files.deleteIfExists(done);
        DemoWorld.retireEverythingWaiting();
    }
}
