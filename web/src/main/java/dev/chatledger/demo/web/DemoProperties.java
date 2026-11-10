package dev.chatledger.demo.web;

import dev.chatledger.security.RateRule;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code app.demo.*}: the public demo (task 9). The switch {@code app.demo.enabled} is off by default and is read where it acts (the
 * conditional beans and the security rules), not here; even when on, the database must be a demo deployment or the application
 * does not start ({@link DemoStartupGuard}). Every value is checked when the application starts.
 *
 * @param lease             how long one visitor keeps a workspace (10 minutes to 4 hours)
 * @param pagePerAddress    requests to {@code /demo} from one address
 * @param claimPerAddress   workspaces asked for from one address
 * @param claimTotal        workspaces asked for in total
 * @param scenarioPerTenant scenario steps in one workspace
 */
@ConfigurationProperties("app.demo")
public record DemoProperties(
        @DefaultValue("30m") Duration lease,
        @DefaultValue("30/10m") String pagePerAddress,
        @DefaultValue("5/1h") String claimPerAddress,
        @DefaultValue("60/1h") String claimTotal,
        @DefaultValue("20/10m") String scenarioPerTenant) {

    public static final Duration MIN_LEASE = Duration.ofMinutes(10);
    public static final Duration MAX_LEASE = Duration.ofHours(4);

    public DemoProperties {
        if (lease == null || lease.compareTo(MIN_LEASE) < 0 || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("app.demo.lease must be between 10 minutes and 4 hours");
        }
        RateRule.parse("app.demo.page-per-address", pagePerAddress);
        RateRule.parse("app.demo.claim-per-address", claimPerAddress);
        RateRule.parse("app.demo.claim-total", claimTotal);
        RateRule.parse("app.demo.scenario-per-tenant", scenarioPerTenant);
    }
}
