package dev.chatledger.demo.web;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The second switch (task 9, D1): with {@code app.demo.enabled=true} the application starts only on a database in {@code DEMO} mode,
 * which holds no real workspace. A demo switched on by mistake against a real database stops the application instead of opening a
 * public door next to real data.
 */
@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoStartupGuard {

    private static final Logger LOG = LoggerFactory.getLogger("demo.startup");

    private final DemoLeases leases;

    public DemoStartupGuard(DemoLeases leases) {
        this.leases = leases;
    }

    @PostConstruct
    void requireDemoDatabase() {
        String mode = leases.mode();
        if (!"DEMO".equals(mode)) {
            throw new IllegalStateException("app.demo.enabled=true needs a demo database (run demo-init on an empty one); this one is " + mode);
        }
        LOG.info("the public demo is on: /demo is open and hands out demo workspaces only");
    }
}
